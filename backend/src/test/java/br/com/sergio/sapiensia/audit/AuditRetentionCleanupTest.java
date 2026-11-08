package br.com.sergio.sapiensia.audit;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditRetentionCleanupTest {

    @Autowired
    private AuditRetentionCleanup.AuditCleaner cleaner;

    @Autowired
    private AuditProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

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
