package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.SapiensiaApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * FR-024, NFR-004: the paid e-mail is scheduled in the payment transaction, survives an application restart and goes
 * out once.
 */
class OrderPaidEmailRestartTest {

    private static final String SUBJECT = "Seu pagamento na SapiensIA foi confirmado";
    private static final String RUNBOOK = "../docs/runbooks/webhook-asaas.md";
    private static final String PLACEHOLDER = "COLE_AQUI_O_ID_DO_PEDIDO";

    // Own container on purpose (same exception as DbSchedulerRestartTest): the database must outlive the Spring
    // context to simulate the restart.
    static PostgreSQLContainer postgres;

    private static JdbcTemplate jdbc;

    @TempDir
    Path outbox;

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

    @Test
    @DisplayName("FR-024, NFR-004: o e-mail agendado antes do reinício sai uma vez depois dele")
    void shouldSendTheScheduledEmailOnceAfterARestart() throws Exception {
        UUID orderId = payAndRestartWithTheTaskPending();

        try (ConfigurableApplicationContext second = startApp("PT1S")) {
            await("the e-mail file", () -> outboxFiles().size() == 1);
            await("payment_email_sent_at", () -> sentAtFilled(orderId));
            await("the task to finish", () -> tasks(orderId) == 0);
        }

        List<Path> files = outboxFiles();
        assertThat(files).hasSize(1);
        assertThat(Files.readString(files.get(0))).contains("Subject: " + SUBJECT);
    }

    @Test
    @DisplayName("FR-024, NFR-004: a tarefa que reaparece depois do envio não envia de novo")
    void shouldNotSendAgainWhenTheTaskReappearsAfterTheSend() throws Exception {
        UUID orderId = payAndRestartWithTheTaskPending();

        try (ConfigurableApplicationContext second = startApp("PT1S")) {
            await("payment_email_sent_at", () -> sentAtFilled(orderId));
            // the row is deleted after the run; inserting before that would hit the on conflict
            await("the first task to finish", () -> tasks(orderId) == 0);
            assertThat(outboxFiles()).hasSize(1);

            assertThat(jdbc.update(runbookSql().replace(PLACEHOLDER, orderId.toString())))
                    .isEqualTo(1);
            await("the re-inserted task to finish", () -> tasks(orderId) == 0);
        }

        assertThat(outboxFiles()).hasSize(1);
    }

    /** First run: the Order is paid with polling off, so the e-mail task stays in the table; then the context closes. */
    private UUID payAndRestartWithTheTaskPending() {
        UUID orderId;
        try (ConfigurableApplicationContext first = startApp("PT1H")) {
            UUID customerId = jdbc.queryForObject(
                    "insert into customers (email, password_hash, email_confirmed_at)"
                            + " values (?, 'x', now()) returning id",
                    UUID.class,
                    "cliente-" + UUID.randomUUID() + "@example.com");
            UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia 100% " + UUID.randomUUID(), 2990);
            orderId = first.getBean(OrderService.class)
                    .create(customerId, "ignored@example.com", new CreateOrderRequest(productId, "52998224725"))
                    .orderId();
            String chargeId =
                    jdbc.queryForObject("select provider_payment_id from orders where id = ?", String.class, orderId);
            String payload = "{\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"" + chargeId + "\"}}";
            UUID eventId = first.getBean(PaymentEvents.class)
                    .insertIfAbsent("evt_" + UUID.randomUUID(), "PAYMENT_RECEIVED", payload, OffsetDateTime.now())
                    .orElseThrow();

            assertThat(first.getBean(PaymentConfirmation.class).process(eventId))
                    .isEqualTo(PaymentResult.PAID);
        }

        assertThat(tasks(orderId)).isEqualTo(1);
        assertThat(outboxFiles()).isEmpty();
        assertThat(sentAtFilled(orderId)).isFalse();
        return orderId;
    }

    private static int tasks(UUID orderId) {
        return jdbc.queryForObject(
                "select count(*) from scheduled_tasks where task_name = 'order-paid-email' and task_instance = ?",
                Integer.class,
                orderId.toString());
    }

    private static boolean sentAtFilled(UUID orderId) {
        return jdbc.queryForObject(
                "select payment_email_sent_at is not null from orders where id = ?", Boolean.class, orderId);
    }

    /** The paid e-mails in the outbox; other e-mails (the application also writes one to the Administrator) don't count. */
    private List<Path> outboxFiles() {
        if (!Files.exists(outbox)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(outbox)) {
            return stream.filter(file -> {
                        try {
                            return Files.readString(file).contains("Subject: " + SUBJECT);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The SQL of the runbook, between its markers; fails clearly when the markers are missing. */
    private static String runbookSql() throws IOException {
        String text = Files.readString(Path.of(RUNBOOK));
        int start = text.indexOf("<!-- resend-email-sql:start -->");
        int end = text.indexOf("<!-- resend-email-sql:end -->");
        assertThat(start).as("start marker in " + RUNBOOK).isGreaterThanOrEqualTo(0);
        assertThat(end).as("end marker in " + RUNBOOK).isGreaterThan(start);
        String block = text.substring(start, end);
        int open = block.indexOf("```sql");
        int close = block.lastIndexOf("```");
        assertThat(open).as("sql block between the markers").isGreaterThanOrEqualTo(0);
        assertThat(close).isGreaterThan(open);
        return block.substring(open + "```sql".length(), close).trim();
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

    private ConfigurableApplicationContext startApp(String pollingInterval) {
        // Command-line arguments (not builder properties) so they win over the values in application.yml.
        return new SpringApplicationBuilder(SapiensiaApplication.class)
                .run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--server.port=0",
                        "--db-scheduler.polling-interval=" + pollingInterval,
                        "--app.email.provider=file",
                        "--app.email.outbox-dir=" + outbox,
                        "--app.public-base-url=http://localhost:5173");
    }
}
