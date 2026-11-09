package br.com.sergio.sapiensia.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The trail is append-only in the database itself. Tests cannot clean the table, so each filters by its own actor. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditImmutabilityTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("SEC-065: shouldRefuseToUpdateARow")
    void shouldRefuseToUpdateARow() {
        UUID actor = insert("0 days");

        assertThatThrownBy(() -> jdbc.update("update audit_events set result = 'FAILURE' where actor_id = ?", actor))
                .rootCause()
                .hasMessageContaining("append-only");

        assertThat(resultOf(actor)).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("SEC-065: shouldRefuseToDeleteARecentRow")
    void shouldRefuseToDeleteARecentRow() {
        UUID actor = insert("1 day");

        assertThatThrownBy(() -> jdbc.update("delete from audit_events where actor_id = ?", actor))
                .rootCause()
                .hasMessageContaining("append-only");

        assertThat(count(actor)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-065: shouldRefuseToTruncate")
    void shouldRefuseToTruncate() {
        UUID actor = insert("0 days");

        assertThatThrownBy(() -> jdbc.execute("truncate table audit_events"))
                .rootCause()
                .hasMessageContaining("append-only");

        assertThat(count(actor)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-037: shouldDeleteARowOlderThan365DaysButNotOneOf364")
    void shouldDeleteARowOlderThan365DaysButNotOneOf364() {
        UUID young = insert("364 days");
        UUID old = insert("366 days");

        assertThatThrownBy(() -> jdbc.update("delete from audit_events where actor_id = ?", young))
                .rootCause()
                .hasMessageContaining("append-only");
        assertThat(jdbc.update("delete from audit_events where actor_id = ?", old))
                .isEqualTo(1);

        assertThat(count(young)).isEqualTo(1);
        assertThat(count(old)).isZero();
    }

    @Test
    @DisplayName("SEC-065: shouldRefuseAFreeTextActionOrResult")
    void shouldRefuseAFreeTextActionOrResult() {
        assertThatThrownBy(() -> jdbc.update("insert into audit_events (occurred_at, action, result, correlation_id)"
                        + " values (now(), 'someone@example.com', 'SUCCESS', gen_random_uuid())"))
                .hasMessageContaining("audit_events_action_check");
        assertThatThrownBy(() -> jdbc.update("insert into audit_events (occurred_at, action, result, correlation_id)"
                        + " values (now(), 'ADMIN_MFA_LOGIN', 'ok 123', gen_random_uuid())"))
                .hasMessageContaining("audit_events_result_check");
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

    private String resultOf(UUID actor) {
        return jdbc.queryForObject("select result from audit_events where actor_id = ?", String.class, actor);
    }
}
