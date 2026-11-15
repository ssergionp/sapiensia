package br.com.sergio.sapiensia.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The Orders: one Product and one Pix charge each (FR-019). */
@Repository
class Orders {

    record Row(
            UUID id,
            UUID customerId,
            UUID productId,
            String productTitle,
            int priceCents,
            OrderStatus status,
            String providerPaymentId,
            Instant createdAt,
            Instant expiresAt) {}

    private static final String SELECT = "select o.id, o.customer_id, o.product_id, p.title, o.price_cents, o.status,"
            + " o.provider_payment_id, o.created_at, o.expires_at"
            + " from orders o join products p on p.id = o.product_id";

    private final JdbcClient jdbc;

    Orders(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getString("title"),
                rs.getInt("price_cents"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getString("provider_payment_id"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", java.time.OffsetDateTime.class).toInstant());
    }

    void insert(
            UUID id,
            UUID customerId,
            UUID productId,
            int priceCents,
            String providerPaymentId,
            Instant createdAt,
            Instant expiresAt) {
        jdbc.sql("insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at)"
                        + " values (:id, :customerId, :productId, :priceCents, 'PENDING', :providerPaymentId,"
                        + " :createdAt, :expiresAt)")
                .param("id", id)
                .param("customerId", customerId)
                .param("productId", productId)
                .param("priceCents", priceCents)
                .param("providerPaymentId", providerPaymentId)
                .param("createdAt", java.sql.Timestamp.from(createdAt))
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .update();
    }

    Optional<Row> findForCustomer(UUID id, UUID customerId) {
        return jdbc.sql(SELECT + " where o.id = :id and o.customer_id = :customerId")
                .param("id", id)
                .param("customerId", customerId)
                .query(Orders::map)
                .optional();
    }

    /** Every Order of the Customer, newest first; no filter on the Product, so an unpublished one stays. */
    List<Row> findAllForCustomer(UUID customerId) {
        return jdbc.sql(SELECT + " where o.customer_id = :customerId order by o.created_at desc, o.id")
                .param("customerId", customerId)
                .query(Orders::map)
                .list();
    }

    Optional<Row> findPending(UUID customerId, UUID productId) {
        return jdbc.sql(SELECT
                        + " where o.customer_id = :customerId and o.product_id = :productId and o.status = 'PENDING'")
                .param("customerId", customerId)
                .param("productId", productId)
                .query(Orders::map)
                .optional();
    }

    List<Row> findPendingExpired(Instant now, int limit) {
        return jdbc.sql(SELECT + " where o.status = 'PENDING' and o.expires_at <= :now"
                        + " order by o.expires_at limit :limit")
                .param("now", java.sql.Timestamp.from(now))
                .param("limit", limit)
                .query(Orders::map)
                .list();
    }

    /** True only for the call that moved the Order from PENDING to EXPIRED. */
    boolean markExpired(UUID id, Instant now) {
        return jdbc.sql("update orders set status = 'EXPIRED', expired_at = :now"
                                + " where id = :id and status = 'PENDING'")
                        .param("now", java.sql.Timestamp.from(now))
                        .param("id", id)
                        .update()
                == 1;
    }

    Optional<Row> findByProviderPaymentId(String providerPaymentId) {
        return jdbc.sql(SELECT + " where o.provider_payment_id = :providerPaymentId")
                .param("providerPaymentId", providerPaymentId)
                .query(Orders::map)
                .optional();
    }

    Optional<Row> findById(UUID id) {
        return jdbc.sql(SELECT + " where o.id = :id")
                .param("id", id)
                .query(Orders::map)
                .optional();
    }

    /** True only for the call that moved the Order from PENDING or EXPIRED to PAID (ADR-0003). */
    boolean markPaid(UUID id, Instant now) {
        return jdbc.sql("update orders set status = 'PAID', paid_at = :now"
                                + " where id = :id and status in ('PENDING', 'EXPIRED')")
                        .param("now", java.sql.Timestamp.from(now))
                        .param("id", id)
                        .update()
                == 1;
    }

    /** Transaction-scoped lock per Customer; must be called inside a transaction. */
    boolean tryLockCustomer(UUID customerId) {
        return Boolean.TRUE.equals(jdbc.sql("select pg_try_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "order:" + customerId)
                .query(Boolean.class)
                .single());
    }
}
