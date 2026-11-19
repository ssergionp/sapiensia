package br.com.sergio.sapiensia.payment;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
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
