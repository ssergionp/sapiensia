package br.com.sergio.sapiensia.payment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** What processing each payment event ended with. Insert and select only: the table is append-only (SEC-028). */
@Repository
class PaymentEventResults {

    private final JdbcClient jdbc;

    PaymentEventResults(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * True when a row was inserted. A final result is kept once (the first one wins); GAVE_UP is inserted only while
     * the event has no final result.
     */
    boolean record(UUID paymentEventId, PaymentResult result, UUID orderId, Instant now) {
        String sql = result.isFinal()
                ? "insert into payment_event_results (payment_event_id, result, order_id, processed_at)"
                        + " values (:eventId, :result, :orderId, :now)"
                        + " on conflict (payment_event_id) where result <> 'GAVE_UP' do nothing"
                : "insert into payment_event_results (payment_event_id, result, order_id, processed_at)"
                        + " select :eventId, :result, :orderId, :now"
                        + " where not exists (select 1 from payment_event_results"
                        + " where payment_event_id = :eventId and result <> 'GAVE_UP')";
        return jdbc.sql(sql)
                        .param("eventId", paymentEventId)
                        .param("result", result.name())
                        .param("orderId", orderId, java.sql.Types.OTHER)
                        .param("now", java.sql.Timestamp.from(now))
                        .update()
                == 1;
    }

    Optional<PaymentResult> findFinal(UUID paymentEventId) {
        return jdbc.sql("select result from payment_event_results"
                        + " where payment_event_id = :eventId and result <> 'GAVE_UP'")
                .param("eventId", paymentEventId)
                .query(String.class)
                .optional()
                .map(PaymentResult::valueOf);
    }
}
