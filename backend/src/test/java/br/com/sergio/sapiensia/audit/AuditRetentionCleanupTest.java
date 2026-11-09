package br.com.sergio.sapiensia.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditRetentionCleanupTest {

    @Autowired
    private AuditRetentionCleanup.AuditCleaner cleaner;

    @Autowired
    private AuditProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("SEC-037: shouldDeleteOnlyEventsOlderThanTheRetention")
    void shouldDeleteOnlyEventsOlderThanTheRetention() {
        UUID old = insert("400 days");
        UUID recent = insert("100 days");

        int deleted = cleaner.deleteExpired();

        // other tests may leave expired rows, so only the rows of this test are checked
        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(count(old)).isZero();
        assertThat(count(recent)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-037: shouldReturnHowManyEventsWereDeleted")
    void shouldReturnHowManyEventsWereDeleted() {
        cleaner.deleteExpired();
        insert("500 days");
        insert("500 days");
        insert("10 days");

        assertThat(cleaner.deleteExpired()).isEqualTo(2);
    }

    @Test
    void shouldUseTheConfiguredRetention() {
        assertThat(properties.retention()).isEqualTo(Duration.ofDays(365));
    }

    @Test
    void shouldRegisterTheDailyCleanupTaskInTheScheduler() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        int rows = 0;
        while (System.nanoTime() < deadline) {
            rows = jdbc.queryForObject(
                    "select count(*) from scheduled_tasks where task_name = 'delete-expired-audit-events'",
                    Integer.class);
            if (rows > 0) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(rows).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"America/New_York", "Europe/Lisbon", "Pacific/Auckland", "UTC"})
    @DisplayName("SEC-037: shouldCutAtTheSameUtcWholeDayInTheCleanupAndInTheTrigger")
    void shouldCutAtTheSameUtcWholeDayInTheCleanupAndInTheTrigger(String zone) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                SingleConnectionDataSource single = new SingleConnectionDataSource(connection, true)) {
            JdbcTemplate session = new JdbcTemplate(single);
            session.execute("set time zone '" + zone + "'");
            OffsetDateTime cutoff = session.queryForObject(
                    "select (date_trunc('day', now() at time zone 'UTC') - interval '365 days') at time zone 'UTC'",
                    OffsetDateTime.class);
            UUID justBefore = insertAt(session, cutoff, "-1 second");
            UUID justAfter = insertAt(session, cutoff, "+1 second");

            // the trigger refuses the row above the boundary
            assertThatThrownBy(() -> session.update("delete from audit_events where actor_id = ?", justAfter))
                    .rootCause()
                    .hasMessageContaining("append-only");

            new AuditEvents(JdbcClient.create(single)).deleteOlderThan(Duration.ofDays(365));

            assertThat(count(session, justBefore)).isZero();
            assertThat(count(session, justAfter)).isEqualTo(1);
        }
    }

    private UUID insertAt(JdbcTemplate session, OffsetDateTime cutoff, String offset) {
        UUID actor = UUID.randomUUID();
        session.update(
                "insert into audit_events (occurred_at, actor_id, action, result, correlation_id)"
                        + " values (?::timestamptz + ?::interval, ?, 'ADMIN_MFA_LOGIN', 'SUCCESS', gen_random_uuid())",
                cutoff.toString(),
                offset,
                actor);
        return actor;
    }

    private int count(JdbcTemplate session, UUID actor) {
        return session.queryForObject("select count(*) from audit_events where actor_id = ?", Integer.class, actor);
    }

    private UUID insert(String age) {
        UUID actor = UUID.randomUUID();
        jdbc.update(
                "insert into audit_events (occurred_at, actor_id, action, result, correlation_id)"
                        + " values (now() - ?::interval, ?, 'ADMIN_MFA_LOGIN', 'SUCCESS', gen_random_uuid())",
                age,
                actor);
        return actor;
    }

    private int count(UUID actor) {
        return jdbc.queryForObject("select count(*) from audit_events where actor_id = ?", Integer.class, actor);
    }
}
