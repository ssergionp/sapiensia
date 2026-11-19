package br.com.sergio.sapiensia.product;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the current Product file of an owner and writes the insert-only {@code download_records}. Package-private. */
@Repository
class ProductDownloads {

    /** The current file of a Product the Customer owns: the object key never leaves the server. */
    record OwnedFile(String objectKey, Instant uploadedAt, String title) {}

    private final JdbcClient jdbc;

    ProductDownloads(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Limits the wait for any lock to 3 s, for the current transaction only ({@code set local}); it must be the first
     * statement. A request stuck behind another one on the Access row then fails with a database error instead of
     * holding a connection.
     */
    void limitLockWait() {
        jdbc.sql("set local lock_timeout = '3s'").update();
    }

    /**
     * The file of a Product with an active Access of the Customer, whatever the status of the Product (ADR-0007). It
     * locks the Access row ({@code for update of a}), so it is only valid inside a transaction: the lock is what
     * serializes the daily limit check.
     */
    Optional<OwnedFile> findOwned(UUID customerId, UUID productId) {
        return jdbc.sql("select f.object_key, f.uploaded_at, p.title"
                        + " from accesses a"
                        + " join product_files f on f.product_id = a.product_id"
                        + " join products p on p.id = a.product_id"
                        + " where a.customer_id = :customerId and a.product_id = :productId"
                        + " and a.cancelled_at is null and a.suspended_at is null"
                        + " for update of a")
                .param("customerId", customerId)
                .param("productId", productId)
                .query((rs, i) ->
                        new OwnedFile(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3)))
                .optional();
    }

    /** Links issued to the Customer for the Product in {@code [from, to)}. */
    long countIssued(UUID customerId, UUID productId, Instant from, Instant to) {
        return jdbc.sql("select count(*) from download_records"
                        + " where customer_id = :customerId and product_id = :productId"
                        + " and downloaded_at >= :from and downloaded_at < :to")
                .param("customerId", customerId)
                .param("productId", productId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query(Long.class)
                .single();
    }

    void record(UUID customerId, UUID productId, Instant fileUploadedAt, Instant now) {
        jdbc.sql("insert into download_records (customer_id, product_id, file_uploaded_at, downloaded_at)"
                        + " values (:customerId, :productId, :uploadedAt, :now)")
                .param("customerId", customerId)
                .param("productId", productId)
                .param("uploadedAt", Timestamp.from(fileUploadedAt))
                .param("now", Timestamp.from(now))
                .update();
    }
}
