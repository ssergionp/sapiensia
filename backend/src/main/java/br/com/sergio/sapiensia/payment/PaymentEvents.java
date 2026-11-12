package br.com.sergio.sapiensia.payment;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The webhook events received from the payment provider, deduplicated by the provider's event id. */
@Repository
class PaymentEvents {

    private final JdbcClient jdbc;

    PaymentEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True when the event was stored; false when an event with the same provider id already existed. */
    boolean insertIfAbsent(String providerEventId, String eventType, String payloadJson, OffsetDateTime receivedAt) {
        return jdbc.sql("insert into payment_events (provider_event_id, event_type, payload, received_at)"
                                + " values (:id, :type, cast(:payload as jsonb), :receivedAt)"
                                + " on conflict (provider_event_id) do nothing")
                        .param("id", providerEventId)
                        .param("type", eventType)
                        .param("payload", payloadJson)
                        .param("receivedAt", receivedAt)
                        .update()
                == 1;
    }
}
