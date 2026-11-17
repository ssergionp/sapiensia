package br.com.sergio.sapiensia.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
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

    static final int ADMIN_PAGE_SIZE = 20;

    /** One line of the Administrator's list; the e-mail is masked in the mapper, so no object holds the full one. */
    record AdminRow(
            UUID id,
            String productTitle,
            int priceCents,
            OrderStatus shownStatus,
            String maskedEmail,
            Instant createdAt,
            Instant paidAt) {}

    /** The shown state (FR-020): a pending Order past its time counts as expired, decided in the SQL itself. */
    private static final String SHOWN_STATUS =
            "case when o.status = 'PENDING' and o.expires_at <= :now then 'EXPIRED' else o.status end";

    private static final String ADMIN_FROM =
            " from orders o join products p on p.id = o.product_id join customers c on c.id = o.customer_id";

    /** The filter on the shown state: constants only, nothing from the request reaches the SQL text. */
    private static String adminWhere(OrderStatus shown) {
        if (shown == null) {
            return "";
        }
        return switch (shown) {
            case PAID -> " where o.status = 'PAID'";
            case PENDING -> " where o.status = 'PENDING' and o.expires_at > :now";
            case EXPIRED -> " where (o.status = 'EXPIRED' or (o.status = 'PENDING' and o.expires_at <= :now))";
            case REFUNDED -> " where o.status = 'REFUNDED'";
        };
    }

    private static AdminRow mapAdmin(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime paidAt = rs.getObject("paid_at", OffsetDateTime.class);
        return new AdminRow(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getInt("price_cents"),
                OrderStatus.valueOf(rs.getString("shown_status")),
                MaskedEmail.of(rs.getString("email")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                paidAt == null ? null : paidAt.toInstant());
    }

    /** One page of every Order, newest first; no filter on the Product, so an unpublished one stays. */
    List<AdminRow> pageForAdministrator(OrderStatus shown, Instant now, int page) {
        return jdbc.sql("select o.id, p.title, o.price_cents, " + SHOWN_STATUS + " as shown_status, c.email,"
                        + " o.created_at, o.paid_at" + ADMIN_FROM + adminWhere(shown)
                        + " order by o.created_at desc, o.id desc limit :limit offset :offset")
                .param("now", Timestamp.from(now))
                .param("limit", ADMIN_PAGE_SIZE)
                .param("offset", (long) page * ADMIN_PAGE_SIZE)
                .query(Orders::mapAdmin)
                .list();
    }

    long countForAdministrator(OrderStatus shown, Instant now) {
        return jdbc.sql("select count(*)" + ADMIN_FROM + adminWhere(shown))
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
    }

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

    /** The PAID Orders created at or after {@code since}. */
    List<Row> findPaidCreatedSince(Instant since) {
        return jdbc.sql(SELECT + " where o.status = 'PAID' and o.created_at >= :since order by o.created_at, o.id")
                .param("since", Timestamp.from(since))
                .query(Orders::map)
                .list();
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

    /** True only for the call that moved the Order from PENDING, EXPIRED or PAID to REFUNDED. */
    boolean markRefunded(UUID id, Instant now) {
        return jdbc.sql("update orders set status = 'REFUNDED', refunded_at = :now"
                                + " where id = :id and status in ('PENDING', 'EXPIRED', 'PAID')")
                        .param("now", java.sql.Timestamp.from(now))
                        .param("id", id)
                        .update()
                == 1;
    }

    /** True while the Order is paid and its confirmation e-mail has not been sent. */
    boolean paymentEmailPending(UUID id) {
        return jdbc.sql("select count(*) from orders where id = :id and status = 'PAID'"
                                + " and payment_email_sent_at is null")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                == 1;
    }

    /** True only for the call that filled the column. */
    boolean markPaymentEmailSent(UUID id, Instant now) {
        return jdbc.sql("update orders set payment_email_sent_at = :now"
                                + " where id = :id and payment_email_sent_at is null")
                        .param("now", java.sql.Timestamp.from(now))
                        .param("id", id)
                        .update()
                == 1;
    }

    /** Records that the e-mail gave up; does nothing once it has been sent. True when a row changed. */
    boolean markPaymentEmailFailed(UUID id, Instant now) {
        return jdbc.sql("update orders set payment_email_failed_at = :now"
                                + " where id = :id and payment_email_sent_at is null")
                        .param("now", java.sql.Timestamp.from(now))
                        .param("id", id)
                        .update()
                == 1;
    }

    /** True while the Order is refunded and its refund e-mail has not been sent. */
    boolean refundEmailPending(UUID id) {
        return jdbc.sql("select count(*) from orders where id = :id and status = 'REFUNDED'"
                                + " and refund_email_sent_at is null")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                == 1;
    }

    /** True only for the call that filled the column. */
    boolean markRefundEmailSent(UUID id, Instant now) {
        return jdbc.sql("update orders set refund_email_sent_at = :now"
                                + " where id = :id and refund_email_sent_at is null")
                        .param("now", java.sql.Timestamp.from(now))
                        .param("id", id)
                        .update()
                == 1;
    }

    /** Records that the refund e-mail gave up; does nothing once it has been sent. True when a row changed. */
    boolean markRefundEmailFailed(UUID id, Instant now) {
        return jdbc.sql("update orders set refund_email_failed_at = :now"
                                + " where id = :id and refund_email_sent_at is null")
                        .param("now", java.sql.Timestamp.from(now))
                        .param("id", id)
                        .update()
                == 1;
    }

    private static final String CUSTOMER_LOCK_PREFIX = "order:";

    /** Transaction-scoped lock per Customer; must be called inside a transaction. */
    boolean tryLockCustomer(UUID customerId) {
        return Boolean.TRUE.equals(jdbc.sql("select pg_try_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", CUSTOMER_LOCK_PREFIX + customerId)
                .query(Boolean.class)
                .single());
    }

    /** Same lock as {@link #tryLockCustomer}, but waits for it; must be called inside a transaction. */
    void lockCustomer(UUID customerId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", CUSTOMER_LOCK_PREFIX + customerId)
                .query()
                .singleRow();
    }

    /** True when the Customer has an Order still PENDING, whatever its expiry time. */
    boolean hasPending(UUID customerId) {
        return jdbc.sql("select exists (select 1 from orders where customer_id = :customerId and status = 'PENDING')")
                .param("customerId", customerId)
                .query(Boolean.class)
                .single();
    }
}
