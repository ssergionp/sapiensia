package br.com.sergio.sapiensia.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.email.EmailMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** NFR-009: grouped, limited alert e-mails to the Administrator. The test calls the dispatch itself. */
@Import({TestcontainersConfiguration.class, AdminAlertDispatchTest.ClockConfig.class})
@SpringBootTest(
        properties = {
            "test.isolated-context=admin-alert-dispatch",
            // the startup task of the Administrator bootstrap must not pick the Administrator these tests create
            "db-scheduler.polling-interval=PT1H"
        })
@ExtendWith(OutputCaptureExtension.class)
class AdminAlertDispatchTest {

    private static final Duration GROUP_WINDOW = Duration.ofMinutes(15);
    private static final String SUBJECT_PREFIX = "Alerta SapiensIA: ";

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void advance(Duration duration) {
            now.updateAndGet(t -> t.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock alertTestClock() {
            return new MutableClock();
        }
    }

    @Autowired
    AdminAlerts alerts;

    @Autowired
    AdminAlertDispatch dispatch;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecordingEmailSender sender;

    @Autowired
    MutableClock clock;

    private String admin;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from admin_alerts");
        jdbc.update("delete from administrators");
        // each test has its own address: the in-memory sender is shared by the whole context
        admin = "admin-" + UUID.randomUUID() + "@example.com";
        jdbc.update("insert into administrators (email, password_hash) values (?, 'x')", admin);
    }

    private List<EmailMessage> alertMails() {
        return sender.messagesTo(admin).stream()
                .filter(m -> m.subject().startsWith(SUBJECT_PREFIX))
                .toList();
    }

    private void recordDistinct(AdminAlertType type) {
        alerts.record(type, null, UUID.randomUUID(), UUID.randomUUID());
    }

    private int rowCount() {
        return jdbc.queryForObject("select count(*) from admin_alerts", Integer.class);
    }

    @Test
    @DisplayName("NFR-009: um alerta gravado vira um e-mail ao Administrador com tipo, id e hora")
    void shouldEmailTheAdministratorTheRecordedAlert() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        alerts.record(AdminAlertType.PAYMENT_EVENT_GAVE_UP, null, orderId, eventId);
        String when = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.of("America/Sao_Paulo"))
                .format(clock.instant());

        AdminAlertDispatch.Result result = dispatch.run();

        assertThat(result).isEqualTo(new AdminAlertDispatch.Result(1, 0, 0));
        assertThat(alertMails()).hasSize(1);
        EmailMessage mail = alertMails().getFirst();
        assertThat(mail.subject()).isEqualTo("Alerta SapiensIA: evento de pagamento não processado (1)");
        assertThat(mail.text()).contains(orderId.toString(), eventId.toString(), when);
    }

    @Test
    @DisplayName("NFR-009: alerta igual repetido só aumenta a contagem")
    void shouldOnlyRaiseTheCounterOfARepeatedAlert() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        for (int i = 0; i < 500; i++) {
            alerts.record(AdminAlertType.PAYMENT_EVENT_NEEDS_REVIEW, "AMOUNT_MISMATCH", orderId, eventId);
        }

        assertThat(rowCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select occurrences from admin_alerts", Integer.class))
                .isEqualTo(500);
    }

    @Test
    @DisplayName("NFR-009: alertas do mesmo tipo na janela viram um e-mail com a contagem")
    void shouldGroupAlertsOfTheSameTypeInTheWindow() {
        for (String detail : List.of("task-a", "task-b", "task-c")) {
            alerts.record(AdminAlertType.TASK_GAVE_UP, detail, null, null);
        }
        dispatch.run();
        assertThat(alertMails()).hasSize(1);
        assertThat(alertMails().getFirst().subject()).endsWith("(3)");

        alerts.record(AdminAlertType.TASK_GAVE_UP, "task-d", null, null);
        alerts.record(AdminAlertType.TASK_GAVE_UP, "task-e", null, null);
        clock.advance(Duration.ofMinutes(5));
        dispatch.run();
        assertThat(alertMails()).hasSize(1);

        clock.advance(Duration.ofMinutes(10));
        dispatch.run();
        assertThat(alertMails()).hasSize(2);
        EmailMessage second = alertMails().get(1);
        assertThat(second.subject()).endsWith("(2)");
        assertThat(second.text()).contains("task-d", "task-e").doesNotContain("task-a", "task-b", "task-c");
    }

    @Test
    @DisplayName("NFR-009: no máximo 8 e-mails de alerta por hora, o mais grave primeiro")
    void shouldSendAtMostEightAlertEmailsPerHourMostSeriousFirst() {
        // the first round holds only the least serious types, one short of the hourly limit of 8, so the second
        // round has room for exactly one mail and proves that the most serious type goes first
        List<AdminAlertType> all = List.of(AdminAlertType.values());
        for (AdminAlertType type : all.subList(all.size() - 7, all.size())) {
            recordDistinct(type);
        }
        assertThat(dispatch.run().sent()).isEqualTo(7);
        assertThat(alertMails()).hasSize(7);

        clock.advance(GROUP_WINDOW);
        for (AdminAlertType type : AdminAlertType.values()) {
            recordDistinct(type);
        }
        assertThat(dispatch.run().sent()).isEqualTo(1);

        assertThat(alertMails()).hasSize(8);
        assertThat(alertMails().get(7).subject()).contains("evento de pagamento não processado");
        assertThat(jdbc.queryForObject("select count(*) from admin_alerts where email_sent_at is null", Integer.class))
                .isEqualTo(7);

        clock.advance(Duration.ofMinutes(45));
        assertThat(dispatch.run().sent()).isEqualTo(7);
        assertThat(alertMails()).hasSize(15);
    }

    @Test
    @DisplayName("NFR-009: o e-mail lista no máximo 20 registros e conta todos")
    void shouldListAtMostTwentyRecordsAndCountAll() {
        for (int i = 1; i <= 25; i++) {
            alerts.record(AdminAlertType.TASK_GAVE_UP, "task-%02d".formatted(i), null, null);
        }

        dispatch.run();

        assertThat(alertMails()).hasSize(1);
        EmailMessage mail = alertMails().getFirst();
        assertThat(mail.subject()).endsWith("(25)");
        assertThat(mail.text().lines().filter(l -> l.startsWith("- ")).count()).isEqualTo(20);
        assertThat(mail.text()).contains("… e mais 5 registros");
        // the newest are listed, the oldest are not
        assertThat(mail.text()).contains("task-25").doesNotContain("task-01");
    }

    @Test
    @DisplayName("ADR-0005: falha no envio repete depois do retry-delay e desiste em 24 h, sem alerta sobre o alerta")
    void shouldRetryAfterTheRetryDelayAndGiveUpAfter24HoursWithoutAlertingAboutIt(CapturedOutput output) {
        sender.failAllSendsTo(admin);
        alerts.record(AdminAlertType.TASK_GAVE_UP, "task-a", null, null);

        assertThat(dispatch.run()).isEqualTo(new AdminAlertDispatch.Result(0, 1, 0));
        dispatch.run();
        assertThat(sender.attemptsTo(admin)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(2));
        dispatch.run();
        assertThat(sender.attemptsTo(admin)).isEqualTo(2);
        assertThat(output.getAll())
                .contains("Admin alert e-mail failed: type=TASK_GAVE_UP reason=EmailDeliveryException");

        int rowsBefore = rowCount();
        clock.advance(Duration.ofHours(24));
        assertThat(dispatch.run()).isEqualTo(new AdminAlertDispatch.Result(0, 0, 1));

        assertThat(sender.attemptsTo(admin)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select email_failed_at is not null from admin_alerts", Boolean.class))
                .isTrue();
        assertThat(output.getAll())
                .containsPattern("ERROR.*Admin alert e-mail gave up: type=TASK_GAVE_UP occurrences=1");
        assertThat(output.getAll().split("Admin alert e-mail gave up", -1)).hasSize(2);
        assertThat(rowCount()).isEqualTo(rowsBefore);
        // later runs leave it alone
        dispatch.run();
        assertThat(sender.attemptsTo(admin)).isEqualTo(2);
    }

    @Test
    @DisplayName("NFR-009: alerta segurado só pelo limite por hora não desiste sem tentar o envio")
    void shouldNotGiveUpARowHeldBackOnlyByTheHourlyCap(CapturedOutput output) {
        Instant now = clock.instant();
        // eight e-mails of other (made-up) types went out just now: the hourly cap is full
        for (int i = 0; i < 8; i++) {
            jdbc.update(
                    "insert into admin_alerts (type, occurred_at, email_sent_at) values (?, ?, ?)",
                    "CAP_FILL_" + "ABCDEFGH".charAt(i),
                    Timestamp.from(now),
                    Timestamp.from(now));
        }
        // a pending row older than the give-up horizon that never had a send attempt
        jdbc.update(
                "insert into admin_alerts (type, detail, occurred_at) values ('TASK_GAVE_UP', 'task-a', ?)",
                Timestamp.from(now.minus(Duration.ofHours(25))));

        AdminAlertDispatch.Result held = dispatch.run();

        assertThat(held).isEqualTo(new AdminAlertDispatch.Result(0, 0, 0));
        assertThat(jdbc.queryForObject(
                        "select count(*) from admin_alerts where type = 'TASK_GAVE_UP' and email_failed_at is null"
                                + " and email_sent_at is null",
                        Integer.class))
                .isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("Admin alert e-mail gave up");
        assertThat(alertMails()).isEmpty();

        clock.advance(Duration.ofMinutes(61));
        assertThat(dispatch.run().sent()).isEqualTo(1);
        assertThat(alertMails()).hasSize(1);
    }

    @Test
    @DisplayName("NFR-009: gravar um alerta igual não espera o envio do e-mail")
    void shouldNotMakeAnIdenticalRecordWaitForTheEmailSend() throws Exception {
        alerts.recordSafely(AdminAlertType.WEBHOOK_REJECTED, "TOKEN_INVALID");
        RecordingEmailSender.Blocker blocker = sender.blockNextSendTo(admin);
        // own threads: the common pool has a single thread on a 2-CPU CI runner, so the record would
        // queue behind the blocked dispatch and the test would measure the pool, not the lock
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<AdminAlertDispatch.Result> dispatching =
                    CompletableFuture.supplyAsync(dispatch::run, threads);
            try {
                assertThat(blocker.awaitEntered(Duration.ofSeconds(10))).isTrue();

                CompletableFuture<Void> recording = CompletableFuture.runAsync(
                        () -> alerts.recordSafely(AdminAlertType.WEBHOOK_REJECTED, "TOKEN_INVALID"), threads);
                try {
                    recording.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    fail("recording an identical alert waited for the e-mail send");
                }
            } finally {
                blocker.release();
                dispatching.get(10, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
        }
        assertThat(alertMails()).hasSize(1);
    }

    @Test
    @DisplayName("NFR-009: sem Administrador cadastrado o despacho não quebra")
    void shouldNotBreakWithoutAnAdministrator(CapturedOutput output) {
        jdbc.update("delete from administrators");
        alerts.record(AdminAlertType.TASK_GAVE_UP, "task-a", null, null);

        AdminAlertDispatch.Result result = dispatch.run();

        assertThat(result).isEqualTo(new AdminAlertDispatch.Result(0, 1, 0));
        assertThat(output.getAll())
                .containsPattern("WARN.*Admin alert e-mail failed: type=TASK_GAVE_UP reason=NO_ADMINISTRATOR");
        assertThat(jdbc.queryForObject(
                        "select count(*) from admin_alerts where email_sent_at is null and email_failed_at is null",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-0006 / SEC-036: detail com texto livre é recusado")
    void shouldRefuseFreeTextDetail(CapturedOutput output) {
        assertThatThrownBy(() -> alerts.record(AdminAlertType.TASK_GAVE_UP, "ana@example.com", UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);

        alerts.recordSafely(AdminAlertType.TASK_GAVE_UP, "ana@example.com");

        assertThat(rowCount()).isZero();
        assertThat(output.getAll())
                .contains("Admin alert not recorded: type=TASK_GAVE_UP reason=IllegalArgumentException")
                .doesNotContain("ana@example.com");
    }

    @Test
    @DisplayName("NFR-009: recordSafely grava o alerta e resolve retira o pendente do tipo e do Pedido")
    void shouldRecordSafelyAndResolve() {
        UUID orderId = UUID.randomUUID();
        alerts.recordSafely(AdminAlertType.WEBHOOK_REJECTED, "TOKEN_INVALID");
        alerts.record(AdminAlertType.ORDER_PAID_EMAIL_GAVE_UP, null, orderId, null);
        assertThat(rowCount()).isEqualTo(2);

        alerts.resolve(AdminAlertType.ORDER_PAID_EMAIL_GAVE_UP, orderId);
        AdminAlertDispatch.Result result = dispatch.run();

        assertThat(result.sent()).isEqualTo(1);
        assertThat(alertMails()).hasSize(1);
        assertThat(alertMails().getFirst().subject()).contains("webhook do Asaas recusado");
    }
}
