package br.com.sergio.sapiensia.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The AuditLog service alone; the MFA flow cases live in the tests of the tasks that audit it. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuditLogTest {

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("SEC-065: shouldRefuseRecordInTransactionOutsideATransaction")
    void shouldRefuseRecordInTransactionOutsideATransaction() {
        UUID actor = UUID.randomUUID();

        assertThatThrownBy(() -> auditLog.recordInTransaction(entry(actor, "203.0.113.7")))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(rows(actor)).isEmpty();
    }

    @Test
    @DisplayName("SEC-065: shouldKeepTheRowOfRecordInTransactionOnlyIfTheTransactionCommits")
    void shouldKeepTheRowOfRecordInTransactionOnlyIfTheTransactionCommits() {
        UUID committed = UUID.randomUUID();
        UUID rolledBack = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> auditLog.recordInTransaction(entry(committed, "203.0.113.7")));
        tx.executeWithoutResult(status -> {
            auditLog.recordInTransaction(entry(rolledBack, "203.0.113.7"));
            status.setRollbackOnly();
        });

        assertThat(rows(committed)).hasSize(1);
        assertThat(rows(rolledBack)).isEmpty();
    }

    @Test
    @DisplayName("SEC-065: shouldKeepTheRowOfRecordIndependentWhenTheCallersTransactionRollsBack")
    void shouldKeepTheRowOfRecordIndependentWhenTheCallersTransactionRollsBack() {
        UUID actor = UUID.randomUUID();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            auditLog.recordIndependent(entry(actor, "203.0.113.7"));
            status.setRollbackOnly();
        });

        assertThat(rows(actor)).hasSize(1);
    }

    @Test
    @DisplayName("SEC-065: shouldNotFailTheCallerAndLogOnlyTheExceptionClassWhenRecordIndependentCannotInsert")
    void shouldLogOnlyTheExceptionClassWhenRecordIndependentCannotInsert() {
        // a temporary trigger makes the database refuse the row; its message carries a marker that must not be logged
        jdbc.execute("create or replace function audit_test_refuse() returns trigger language plpgsql as $$ begin"
                + " raise exception 'MARKER-7f3a-refused'; end $$");
        jdbc.execute("create trigger audit_test_refuse before insert on audit_events for each row"
                + " when (new.origin = '198.51.100.99') execute function audit_test_refuse()");
        Logger logger = (Logger) LoggerFactory.getLogger(AuditLog.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        UUID actor = UUID.randomUUID();
        try {
            auditLog.recordIndependent(entry(actor, "198.51.100.99"));
        } finally {
            logger.detachAppender(appender);
            jdbc.execute("drop trigger audit_test_refuse on audit_events");
            jdbc.execute("drop function audit_test_refuse()");
        }

        assertThat(rows(actor)).isEmpty();
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("org.springframework.");
            assertThat(event.getFormattedMessage()).doesNotContain("MARKER-7f3a");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    @DisplayName("SEC-065: shouldUseTheCorrelationIdOfTheMdc")
    void shouldUseTheCorrelationIdOfTheMdc() {
        UUID actor = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        MDC.put("correlationId", correlationId.toString());

        auditLog.recordIndependent(entry(actor, "203.0.113.7"));

        assertThat(rows(actor))
                .singleElement()
                .satisfies(row -> assertThat(row.get("correlation_id")).isEqualTo(correlationId));
    }

    @Test
    @DisplayName("SEC-065: shouldCreateACorrelationIdWhenTheMdcHasNone")
    void shouldCreateACorrelationIdWhenTheMdcHasNone() {
        UUID actor = UUID.randomUUID();

        auditLog.recordIndependent(entry(actor, "203.0.113.7"));

        assertThat(rows(actor))
                .singleElement()
                .satisfies(row -> assertThat(row.get("correlation_id")).isNotNull());
    }

    @Test
    @DisplayName("SEC-065: shouldDropTheZoneOfAnIpv6Origin")
    void shouldDropTheZoneOfAnIpv6Origin() {
        UUID actor = UUID.randomUUID();

        auditLog.recordIndependent(entry(actor, "fe80::1%eth0"));

        assertThat(jdbc.queryForObject("select host(origin) from audit_events where actor_id = ?", String.class, actor))
                .isEqualTo("fe80::1");
    }

    @Test
    @DisplayName("SEC-065: shouldAcceptEntriesWithoutActorObjectAndOrigin")
    void shouldAcceptEntriesWithoutActorObjectAndOrigin() {
        UUID correlationId = UUID.randomUUID();
        MDC.put("correlationId", correlationId.toString());

        auditLog.recordIndependent(
                new AuditEntry(AuditAction.ADMIN_PASSWORD_LOGIN, AuditResult.FAILURE, null, null, null, null));

        Map<String, Object> row =
                jdbc.queryForMap("select * from audit_events where correlation_id = ?", correlationId);
        assertThat(row.get("actor_id")).isNull();
        assertThat(row.get("object_type")).isNull();
        assertThat(row.get("object_id")).isNull();
        assertThat(row.get("origin")).isNull();
        assertThat(row.get("action")).isEqualTo("ADMIN_PASSWORD_LOGIN");
        assertThat(row.get("result")).isEqualTo("FAILURE");
    }

    @Test
    @DisplayName("SEC-065: shouldHaveExactlyTheExpectedColumns")
    void shouldHaveExactlyTheExpectedColumns() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'audit_events'"
                        + " order by ordinal_position",
                String.class);

        assertThat(columns)
                .containsExactly(
                        "id",
                        "occurred_at",
                        "actor_id",
                        "action",
                        "object_type",
                        "object_id",
                        "result",
                        "correlation_id",
                        "origin");
    }

    private static AuditEntry entry(UUID actor, String origin) {
        return new AuditEntry(
                AuditAction.ADMIN_MFA_LOGIN, AuditResult.SUCCESS, actor, AuditObject.ADMINISTRATOR, actor, origin);
    }

    private List<Map<String, Object>> rows(UUID actor) {
        return jdbc.queryForList("select * from audit_events where actor_id = ?", actor);
    }
}
