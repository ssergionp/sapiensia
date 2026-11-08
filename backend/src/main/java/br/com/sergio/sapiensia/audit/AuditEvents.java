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

    /** The cutoff is computed by the database clock, the same one the trigger uses. */
    int deleteOlderThan(Duration retention) {
        return jdbc.sql("delete from audit_events where occurred_at < now() - make_interval(secs => :seconds)")
                .param("seconds", retention.toMillis() / 1000.0)
                .update();
    }
}
