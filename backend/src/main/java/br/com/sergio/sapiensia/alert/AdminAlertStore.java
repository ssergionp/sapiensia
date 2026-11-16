package br.com.sergio.sapiensia.alert;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Access to the {@code admin_alerts} table. Package-private: other features use {@link AdminAlerts}. */
@Repository
class AdminAlertStore {

    /** A row that has not been e-mailed, given up or resolved. */
    private static final String PENDING = "email_sent_at is null and email_failed_at is null and resolved_at is null";

    record Pending(long id, Instant occurredAt, String detail, UUID paymentEventId, UUID orderId, int occurrences) {}

    private final JdbcClient jdbc;

    AdminAlertStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the alert, or raises the counter of the identical pending one. Joins the caller's transaction. */
    void upsert(AdminAlertType type, String detail, UUID orderId, UUID paymentEventId, Instant now) {
        jdbc.sql("insert into admin_alerts (type, detail, order_id, payment_event_id, occurred_at)"
                        + " values (:type, :detail, :orderId, :eventId, :now)"
                        + " on conflict (type, detail, order_id, payment_event_id) where " + PENDING
                        + " do update set occurrences = admin_alerts.occurrences + 1")
                .param("type", type.name())
                .param("detail", detail)
                .param("orderId", orderId)
                .param("eventId", paymentEventId)
                .param("now", Timestamp.from(now))
                .update();
    }

    void resolve(AdminAlertType type, UUID orderId, Instant now) {
        jdbc.sql("update admin_alerts set resolved_at = :now where type = :type and order_id = :orderId and " + PENDING)
                .param("now", Timestamp.from(now))
                .param("type", type.name())
                .param("orderId", orderId)
                .update();
    }

    /** E-mails sent since {@code since}: the rows of one e-mail share the same {@code email_sent_at}. */
    int countEmailsSentSince(Instant since) {
        return jdbc.sql("select count(*) from (select distinct type, email_sent_at from admin_alerts"
                        + " where email_sent_at > :since) sent")
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    boolean sentSince(AdminAlertType type, Instant since) {
        return jdbc.sql("select exists (select 1 from admin_alerts where type = :type and email_sent_at > :since)")
                .param("type", type.name())
                .param("since", Timestamp.from(since))
                .query(Boolean.class)
                .single();
    }

    /** True when a failed attempt for this type is newer than {@code since}. */
    boolean attemptedSince(AdminAlertType type, Instant since) {
        return jdbc.sql("select exists (select 1 from admin_alerts where type = :type and email_attempted_at > :since"
                        + " and " + PENDING + ")")
                .param("type", type.name())
                .param("since", Timestamp.from(since))
                .query(Boolean.class)
                .single();
    }

    /**
     * Marks as given up the pending rows of the type first seen before {@code cutoff} that had a failed send attempt;
     * returns their occurrences. A row held back only by the window or the hourly cap is never given up.
     */
    int giveUp(AdminAlertType type, Instant cutoff, Instant now) {
        return jdbc.sql("with given_up as (update admin_alerts set email_failed_at = :now"
                        + " where type = :type and occurred_at <= :cutoff and email_attempted_at is not null and "
                        + PENDING
                        + " returning occurrences) select coalesce(sum(occurrences), 0)::int from given_up")
                .param("now", Timestamp.from(now))
                .param("type", type.name())
                .param("cutoff", Timestamp.from(cutoff))
                .query(Integer.class)
                .single();
    }

    /** Reads the pending rows of the type, newest first, without locking them: the e-mail is sent outside any lock. */
    List<Pending> findPending(AdminAlertType type) {
        return jdbc
                .sql("select id, occurred_at, detail, payment_event_id, order_id, occurrences from admin_alerts"
                        + " where type = :type and " + PENDING)
                .param("type", type.name())
                .query((rs, i) -> new Pending(
                        rs.getLong("id"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("detail"),
                        rs.getObject("payment_event_id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getInt("occurrences")))
                .list()
                .stream()
                .sorted(Comparator.comparing(Pending::occurredAt)
                        .thenComparingLong(Pending::id)
                        .reversed())
                .toList();
    }

    /** Marks those rows as sent, if they are still pending. Run in a short transaction after the e-mail went out. */
    void markSent(List<Long> ids, Instant now) {
        jdbc.sql("update admin_alerts set email_sent_at = :now where id = any(:ids) and " + PENDING)
                .param("now", Timestamp.from(now))
                .param("ids", ids.toArray(new Long[0]))
                .update();
    }

    /** Remembers a failed attempt on the pending rows of the type, for the retry delay. */
    void markAttempted(AdminAlertType type, Instant now) {
        jdbc.sql("update admin_alerts set email_attempted_at = :now where type = :type and " + PENDING)
                .param("now", Timestamp.from(now))
                .param("type", type.name())
                .update();
    }
}
