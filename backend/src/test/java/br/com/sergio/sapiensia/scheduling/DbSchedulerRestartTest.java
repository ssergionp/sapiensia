package br.com.sergio.sapiensia.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.SapiensiaApplication;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Spike for db-scheduler on Java 25 and PostgreSQL: tasks run, and pending and recurring tasks survive a restart of the
 * Spring context.
 */
class DbSchedulerRestartTest {

    private static final long TIMEOUT_SECONDS = 20;

    // This class creates its own container instead of using TestcontainersConfiguration on purpose: the database must
    // stay alive while the Spring context is shut down and started again to simulate the restart, and the default
    // container dies together with the context. This is a deliberate exception to the "no container per test class"
    // convention.
    static PostgreSQLContainer postgres;

    private static JdbcTemplate jdbc;

    @TempDir
    static Path outboxDir;

    private static CountDownLatch oneTimeLatch;
    private static AtomicReference<String> oneTimePayload;
    private static CountDownLatch recurringLatch;

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
        postgres.start();
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    @BeforeEach
    void resetProbes() {
        oneTimeLatch = new CountDownLatch(1);
        oneTimePayload = new AtomicReference<>();
        recurringLatch = new CountDownLatch(1);
        // Rows left by a previous test must not influence this one (the table exists after the first start).
        if (Boolean.TRUE.equals(
                jdbc.queryForObject("select to_regclass('scheduled_tasks') is not null", Boolean.class))) {
            jdbc.update("delete from scheduled_tasks");
        }
    }

    @Test
    @DisplayName("NFR-004: executa uma tarefa única com payload no PostgreSQL")
    void shouldRunOneTimeTaskWithPayloadOnPostgres() throws Exception {
        try (ConfigurableApplicationContext context = startApp(Duration.ofSeconds(1))) {
            scheduleProbe(context, "payload-é-ç");

            assertThat(oneTimeLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(oneTimePayload.get()).isEqualTo("payload-é-ç");
        }
    }

    @Test
    @DisplayName("NFR-004: executa uma tarefa pendente depois do reinício")
    void shouldRunPendingTaskAfterRestart() throws Exception {
        // Polling every hour: the first read happens at startup, before the task is scheduled, so nothing runs.
        ConfigurableApplicationContext first = startApp(Duration.ofHours(1));
        try {
            scheduleProbe(first, "pending-payload");
        } finally {
            first.close();
        }

        assertThat(oneTimeLatch.getCount()).isEqualTo(1);
        Boolean picked = jdbc.queryForObject(
                "select picked from scheduled_tasks where task_name = 'probe-one-time'", Boolean.class);
        assertThat(picked).isFalse();

        try (ConfigurableApplicationContext second = startApp(Duration.ofSeconds(1))) {
            assertThat(oneTimeLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(oneTimePayload.get()).isEqualTo("pending-payload");
        }
    }

    @Test
    @DisplayName("NFR-004: a tarefa recorrente continua repetindo depois do reinício")
    void shouldKeepRecurringTaskRepeatingAfterRestart() throws Exception {
        try (ConfigurableApplicationContext first = startApp(Duration.ofSeconds(1))) {
            assertThat(recurringLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        recurringLatch = new CountDownLatch(1);

        try (ConfigurableApplicationContext second = startApp(Duration.ofSeconds(1))) {
            assertThat(recurringLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Integer rows = jdbc.queryForObject(
                    "select count(*) from scheduled_tasks where task_name = 'probe-recurring'", Integer.class);
            assertThat(rows).isEqualTo(1);
        }
    }

    private static void scheduleProbe(ConfigurableApplicationContext context, String payload) {
        Scheduler scheduler = context.getBean(Scheduler.class);
        scheduler.schedule(
                new TaskInstance<>("probe-one-time", "instance-1", payload),
                Instant.now().plusSeconds(1));
    }

    private static ConfigurableApplicationContext startApp(Duration pollingInterval) {
        // Command-line arguments (not builder properties) so they win over the values in application.yml.
        return new SpringApplicationBuilder(SapiensiaApplication.class, TestTasks.class)
                .run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--server.port=0",
                        "--db-scheduler.polling-interval=" + pollingInterval,
                        // without TestcontainersConfiguration there is no fake sender: write e-mails to a temp folder
                        "--app.email.provider=file",
                        "--app.email.outbox-dir=" + outboxDir,
                        "--app.public-base-url=http://localhost:5173");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestTasks {

        @Bean
        OneTimeTask<String> probeOneTime() {
            return Tasks.oneTime("probe-one-time", String.class).execute((instance, ctx) -> {
                oneTimePayload.set(instance.getData());
                oneTimeLatch.countDown();
            });
        }

        @Bean
        RecurringTask<Void> probeRecurring() {
            return Tasks.recurring("probe-recurring", FixedDelay.ofSeconds(1)).execute((instance, ctx) -> {
                recurringLatch.countDown();
            });
        }
    }
}
