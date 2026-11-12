package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The financial record is append-only in the database itself, and closed to every role but the application. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FinancialRecordImmutabilityTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-11T12:00:00Z");
    private static final String MARKER = "secret-payload-marker";

    @Autowired
    private PaymentEvents events;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("SEC-028: shouldRefuseToUpdateARow")
    void shouldRefuseToUpdateARow() {
        String id = insertEvent();

        assertRefusedByTheGuard(() -> jdbc.update(
                "update payment_events set event_type = 'PAYMENT_REFUNDED' where provider_event_id = ?", id));

        assertThat(eventTypeOf(id)).isEqualTo("PAYMENT_RECEIVED");
    }

    @Test
    @DisplayName("SEC-028: shouldRefuseToDeleteARow")
    void shouldRefuseToDeleteARow() {
        String id = insertEvent();

        assertRefusedByTheGuard(() -> jdbc.update("delete from payment_events where provider_event_id = ?", id));

        assertThat(count(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-028: shouldRefuseToTruncate")
    void shouldRefuseToTruncate() {
        String id = insertEvent();

        assertRefusedByTheGuard(() -> jdbc.execute("truncate table payment_events"));
        assertRefusedByTheGuard(() -> jdbc.execute("truncate table payment_events cascade"));

        assertThat(count(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-028: shouldRefuseAnUpsertThatWouldChangeARow")
    void shouldRefuseAnUpsertThatWouldChangeARow() {
        String id = insertEvent();

        assertRefusedByTheGuard(() -> jdbc.update(
                "insert into payment_events (provider_event_id, event_type, payload, received_at)"
                        + " values (?, 'PAYMENT_REFUNDED', '{}'::jsonb, now())"
                        + " on conflict (provider_event_id) do update set event_type = excluded.event_type",
                id));

        assertThat(eventTypeOf(id)).isEqualTo("PAYMENT_RECEIVED");
    }

    @Test
    @DisplayName("SEC-028: shouldHideTheRowsFromAnotherRole")
    void shouldHideTheRowsFromAnotherRole() {
        String id = insertEvent();
        String role = "probe_" + UUID.randomUUID().toString().substring(0, 8);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("create role " + role);
            jdbc.execute("grant select, insert on payment_events to " + role);
            jdbc.execute("set local role " + role);

            assertThat(jdbc.queryForObject(
                            "select count(*) from payment_events where provider_event_id = ?", Integer.class, id))
                    .isZero();
            assertThatThrownBy(() -> jdbc.update(
                            "insert into payment_events (provider_event_id, event_type, payload, received_at)"
                                    + " values (?, 'PAYMENT_RECEIVED', '{}'::jsonb, now())",
                            "evt_" + UUID.randomUUID()))
                    .rootCause()
                    .isInstanceOf(PSQLException.class)
                    .satisfies(
                            e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("42501"))
                    .hasMessageContaining("row-level security");

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("SEC-028: shouldStillInsertAndDeduplicate")
    void shouldStillInsertAndDeduplicate() {
        String id = "evt_" + UUID.randomUUID();

        assertThat(events.insertIfAbsent(id, "PAYMENT_RECEIVED", "{}", NOW)).isTrue();
        assertThat(events.insertIfAbsent(id, "PAYMENT_RECEIVED", "{}", NOW)).isFalse();

        assertThat(count(id)).isEqualTo(1);
    }

    private void assertRefusedByTheGuard(ThrowingCallable attempt) {
        assertThatThrownBy(attempt).rootCause().isInstanceOfSatisfying(PSQLException.class, e -> {
            assertThat(e.getSQLState()).isEqualTo("42501");
            assertThat(e.getServerErrorMessage().getMessage()).isEqualTo("payment_events is append-only");
            assertThat(e.getMessage()).doesNotContain(MARKER);
        });
    }

    private String insertEvent() {
        String id = "evt_" + UUID.randomUUID();
        events.insertIfAbsent(id, "PAYMENT_RECEIVED", "{\"marker\":\"" + MARKER + "\"}", NOW);
        return id;
    }

    private int count(String id) {
        return jdbc.queryForObject(
                "select count(*) from payment_events where provider_event_id = ?", Integer.class, id);
    }

    private String eventTypeOf(String id) {
        return jdbc.queryForObject(
                "select event_type from payment_events where provider_event_id = ?", String.class, id);
    }
}
