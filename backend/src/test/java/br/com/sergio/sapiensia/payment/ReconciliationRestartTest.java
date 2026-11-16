package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.SapiensiaApplication;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.TestcontainersConfiguration.TestDatabase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** NFR-004, SEC-030: the nightly reconciliation is a recurring task at 03:30 in São Paulo that survives a restart. */
class ReconciliationRestartTest {

    private static final String PRODUCTION_CRON = "0 30 3 * * *";
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    // Own database on purpose (same as DbSchedulerRestartTest): it outlives the Spring contexts to simulate a restart.
    static TestDatabase postgres;

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startDatabase() {
        postgres = TestcontainersConfiguration.newDatabase();
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(postgres.jdbcUrl(), postgres.username(), postgres.password()));
    }

    @AfterAll
    static void stopDatabase() {
        postgres.drop();
    }

    @Test
    @DisplayName("NFR-004, SEC-030: a conciliação fica agendada para as 03:30 de São Paulo")
    void shouldBeScheduledForHalfPastThreeInSaoPaulo() {
        resetTable();
        Instant before = Instant.now();
        try (ConfigurableApplicationContext first = startApp(PRODUCTION_CRON, "PT1H")) {
            List<Instant> times = executionTimes();
            assertThat(times).hasSize(1);
            Instant next = times.get(0);
            assertThat(next).isAfter(before);
            assertThat(next).isBeforeOrEqualTo(Instant.now().plus(Duration.ofHours(25)));
            assertThat(isHalfPastThree(next)).isTrue();
        }
    }

    @Test
    @DisplayName("NFR-004: a noite perdida com o sistema parado roda depois do reinício")
    void shouldRunTheMissedNightAfterARestart() throws Exception {
        resetTable();
        try (ConfigurableApplicationContext first = startApp(PRODUCTION_CRON, "PT1H")) {
            assertThat(executionTimes()).hasSize(1);
            // the system was down at 03:30: the execution is overdue
            jdbc.update(
                    "update scheduled_tasks set execution_time = now() - interval '1 hour' where task_name = ?",
                    ReconciliationTasks.TASK_NAME);
        }

        try (ConfigurableApplicationContext second = startApp(PRODUCTION_CRON, "PT1S")) {
            SwitchablePixGateway gateway = second.getBean(SwitchablePixGateway.class);
            await("the listing of charges", () -> gateway.count("LIST_CHARGES") >= 1);
            await("the next execution", () -> {
                List<Instant> times = executionTimes();
                return times.size() == 1 && times.get(0).isAfter(Instant.now());
            });
            assertThat(isHalfPastThree(executionTimes().get(0))).isTrue();
        }
    }

    @Test
    @DisplayName("NFR-004: com o cron \"-\" a conciliação não fica agendada")
    void shouldNotBeScheduledWithTheDisabledCron() {
        resetTable();
        try (ConfigurableApplicationContext context = startApp("-", "PT1H")) {
            assertThat(executionTimes()).isEmpty();
        }
    }

    private static void resetTable() {
        // the table only exists after the first context ran the migrations
        Boolean exists = jdbc.queryForObject("select to_regclass('scheduled_tasks') is not null", Boolean.class);
        if (Boolean.TRUE.equals(exists)) {
            jdbc.update("delete from scheduled_tasks where task_name = ?", ReconciliationTasks.TASK_NAME);
        }
    }

    private static List<Instant> executionTimes() {
        return jdbc
                .queryForList(
                        "select execution_time from scheduled_tasks where task_name = ?",
                        Timestamp.class,
                        ReconciliationTasks.TASK_NAME)
                .stream()
                .map(Timestamp::toInstant)
                .toList();
    }

    private static boolean isHalfPastThree(Instant instant) {
        return instant.atZone(SAO_PAULO).toLocalTime().equals(LocalTime.of(3, 30));
    }

    /** Polls every 100 ms, for at most 20 s; never a fixed wait. */
    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    private ConfigurableApplicationContext startApp(String cron, String pollingInterval) {
        // Command-line arguments (not builder properties) so they win over the values in application.yml.
        return new SpringApplicationBuilder(SapiensiaApplication.class, SwitchablePixGatewayConfig.class)
                .run(
                        "--spring.datasource.url=" + postgres.jdbcUrl(),
                        "--spring.datasource.username=" + postgres.username(),
                        "--spring.datasource.password=" + postgres.password(),
                        "--server.port=0",
                        "--db-scheduler.polling-interval=" + pollingInterval,
                        "--app.reconciliation.cron=" + cron,
                        "--app.email.provider=file",
                        "--app.email.outbox-dir=target/test-outbox-reconciliation",
                        "--app.public-base-url=http://localhost:5173");
    }
}
