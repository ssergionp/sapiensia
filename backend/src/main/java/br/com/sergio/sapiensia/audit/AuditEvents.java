package br.com.sergio.sapiensia.audit;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Table audit_events: insert and delete of expired rows only (the database trigger forbids anything else). */
@Repository
class AuditEvents {

    private final JdbcClient jdbc;

    AuditEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(OffsetDateTime occurredAt, AuditEntry entry, UUID correlationId, String origin) {
        jdbc.sql("insert into audit_events (occurred_at, actor_id, action, object_type, object_id, result,"
                        + " correlation_id, origin) values (:occurredAt, cast(:actorId as uuid), :action,"
                        + " cast(:objectType as varchar), cast(:objectId as uuid), :result, :correlationId,"
                        + " cast(:origin as inet))")
                .param("occurredAt", occurredAt)
                .param("actorId", entry.actorId())
                .param("action", entry.action().name())
                .param(
                        "objectType",
                        entry.objectType() == null ? null : entry.objectType().name())
                .param("objectId", entry.objectId())
                .param("result", entry.result().name())
                .param("correlationId", correlationId)
                .param("origin", origin)
                .update();
    }

    /** Same cutoff as the trigger (V12): start of the current UTC day minus whole days, whatever the session time zone. */
    int deleteOlderThan(Duration retention) {
        return jdbc.sql(
                        "delete from audit_events where occurred_at <"
                                + " (date_trunc('day', now() at time zone 'UTC') - make_interval(days => :days)) at time zone 'UTC'")
                .param("days", (int) retention.toDays())
                .update();
    }
}
