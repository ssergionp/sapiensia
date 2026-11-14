package br.com.sergio.sapiensia.payment;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The webhook events received from the payment provider, deduplicated by the provider's event id. */
@Repository
class PaymentEvents {

    /** The event without its body; chargeId is payment.id of the payload and may be null. */
    record Ref(String providerEventId, String eventType, String chargeId) {}

    private final JdbcClient jdbc;

    PaymentEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The internal id when the event was stored; empty when an event with the same provider id already existed. */
    Optional<UUID> insertIfAbsent(
            String providerEventId, String eventType, String payloadJson, OffsetDateTime receivedAt) {
        return jdbc.sql("insert into payment_events (provider_event_id, event_type, payload, received_at)"
                        + " values (:id, :type, cast(:payload as jsonb), :receivedAt)"
                        + " on conflict (provider_event_id) do nothing returning id")
                .param("id", providerEventId)
                .param("type", eventType)
                .param("payload", payloadJson)
                .param("receivedAt", receivedAt)
                .query(UUID.class)
                .optional();
    }

    /** Reads only the charge id from the payload; the whole body is never loaded. */
    Optional<Ref> findRef(UUID id) {
        return jdbc.sql("select provider_event_id, event_type, payload->'payment'->>'id' as charge_id"
                        + " from payment_events where id = :id")
                .param("id", id)
                .query((rs, n) -> new Ref(
                        rs.getString("provider_event_id"), rs.getString("event_type"), rs.getString("charge_id")))
                .optional();
    }
}
