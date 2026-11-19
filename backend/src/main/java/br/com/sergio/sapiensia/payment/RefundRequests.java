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

/** The Customers' Refund requests (FR-023): one per Order at most. */
@Repository
class RefundRequests {

    /**
     * How many OTHER Orders of the same Customer were refunded, or had a Refund requested, at or after {@code since}.
     * Each Order counts once. A correlated subquery over the Order aliased {@code o}, so the request and the
     * Administrator's page share one definition of the rule; {@code since} is an SQL expression (a parameter or a
     * column expression), never text from a request.
     */
    static String otherRefundsSql(String since) {
        return "(select count(*) from orders other where other.customer_id = o.customer_id and other.id <> o.id"
                + " and ((other.status = 'REFUNDED' and other.refunded_at >= " + since + ")"
                + " or exists (select 1 from refund_requests rr where rr.order_id = other.id"
                + " and rr.requested_at >= " + since + ")))";
    }

    static final int ADMIN_PAGE_SIZE = 20;

    private static final String ADMIN_FROM = " from refund_requests r join orders o on o.id = r.order_id"
            + " join products p on p.id = o.product_id join customers c on c.id = o.customer_id";

    /** The filter: constants only, nothing from the request reaches the SQL text. */
    private static String adminWhere(RefundRequestFilter filter) {
        if (filter == null) {
            return "";
        }
        return switch (filter) {
            case OPEN -> " where o.status = 'PAID'";
            case REFUNDED -> " where o.status = 'REFUNDED'";
        };
    }

    /**
     * Twelve calendar months before the request, on the São Paulo calendar: the same as {@code
     * requestedAt.atZone(RefundWindow.ZONE).minusMonths(12)} that {@link RefundRequestService} uses.
     */
    private static final String SINCE_TWELVE_MONTHS = "((r.requested_at at time zone '" + RefundWindow.ZONE.getId()
            + "') - interval '12 months') at time zone '" + RefundWindow.ZONE.getId() + "'";

    /** The page's line; the e-mail is masked in the mapper, so no object holds the full one. */
    private static AdminRefundRequestItem mapAdmin(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime paidAt = rs.getObject("paid_at", OffsetDateTime.class);
        OffsetDateTime refundedAt = rs.getObject("refunded_at", OffsetDateTime.class);
        return new AdminRefundRequestItem(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getInt("price_cents"),
                MaskedEmail.of(rs.getString("email")),
                RefundReason.valueOf(rs.getString("reason")),
                rs.getString("details"),
                rs.getObject("requested_at", OffsetDateTime.class).toInstant(),
                paidAt == null ? null : paidAt.toInstant(),
                rs.getLong("downloads"),
                rs.getInt("other_refunds"),
                rs.getString("provider_payment_id"),
                refundedAt == null ? null : refundedAt.toInstant());
    }

    /** One page of the requests, newest first; Orders without a request are not here. */
    List<AdminRefundRequestItem> pageForAdministrator(RefundRequestFilter filter, int page) {
        return jdbc.sql("select o.id, p.title, o.price_cents, c.email, r.reason, r.details, r.requested_at,"
                        + " o.paid_at, o.provider_payment_id, o.refunded_at,"
                        + " (select count(*) from download_records d where d.customer_id = o.customer_id"
                        + " and d.product_id = o.product_id) as downloads,"
                        + " " + otherRefundsSql(SINCE_TWELVE_MONTHS) + " as other_refunds"
                        + ADMIN_FROM + adminWhere(filter)
                        + " order by r.requested_at desc, r.id desc limit :limit offset :offset")
                .param("limit", ADMIN_PAGE_SIZE)
                .param("offset", (long) page * ADMIN_PAGE_SIZE)
                .query(RefundRequests::mapAdmin)
                .list();
    }

    long countForAdministrator(RefundRequestFilter filter) {
        return jdbc.sql("select count(*)" + ADMIN_FROM + adminWhere(filter))
                .query(Long.class)
                .single();
    }

    private final JdbcClient jdbc;

    RefundRequests(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True only when this call stored the request; false when the Order already had one. */
    boolean insert(UUID id, UUID orderId, UUID customerId, RefundReason reason, String details, Instant now) {
        return jdbc.sql("insert into refund_requests (id, order_id, customer_id, reason, details, requested_at)"
                                + " values (:id, :orderId, :customerId, :reason, :details, :now)"
                                + " on conflict (order_id) do nothing")
                        .param("id", id)
                        .param("orderId", orderId)
                        .param("customerId", customerId)
                        .param("reason", reason.name())
                        .param("details", details)
                        .param("now", Timestamp.from(now))
                        .update()
                == 1;
    }

    Optional<Instant> requestedAt(UUID orderId) {
        return jdbc.sql("select requested_at from refund_requests where order_id = :orderId")
                .param("orderId", orderId)
                .query((rs, rowNum) ->
                        rs.getObject("requested_at", OffsetDateTime.class).toInstant())
                .optional();
    }

    /** See {@link #otherRefundsSql}; 0 when the Order is not this Customer's. */
    int otherRefunds(UUID customerId, UUID orderId, Instant since) {
        return jdbc.sql("select coalesce((select " + otherRefundsSql(":since")
                        + " from orders o where o.id = :orderId and o.customer_id = :customerId), 0)")
                .param("since", Timestamp.from(since))
                .param("orderId", orderId)
                .param("customerId", customerId)
                .query(Integer.class)
                .single();
    }
}
