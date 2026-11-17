package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.TOKEN;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.paymentEvent;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.alert.AdminAlertTestAccess;
import br.com.sergio.sapiensia.email.EmailMessage;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * NFR-009: every origin of an Administrator alert writes its row. Same Spring context as PaymentEventProcessingTest
 * (same imports and properties), so the scheduler tasks really run; the test calls the dispatch itself. It lives in
 * the payment package because the payment beans are package-private.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=payment-event-processing",
            "app.login-limit.ip-max-attempts=1000",
            "db-scheduler.polling-interval=PT0.2S",
            "app.orders.payment-processing.retry-delay=PT0.5S",
            "app.orders.payment-processing.max-attempts=5"
        })
class AdminAlertOriginsTest {

    private static final int PRICE = 2990;
    private static final String SUBJECT_PREFIX = "Alerta SapiensIA: ";
    private static final String LOOPBACK = "127.0.0.1";
    private static final Map<String, String> AUTH = Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN);
    private static final Map<String, String> WRONG_TOKEN = Map.of(AsaasWebhookAccess.TOKEN_HEADER, "wrong-token");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SwitchablePixGateway gateway;

    @Autowired
    RecordingEmailSender sender;

    @Autowired
    Orders orders;

    @Autowired
    PaymentEvents events;

    @Autowired
    PaymentEventOutcome outcome;

    @Autowired
    PaymentConfirmation confirmation;

    @Autowired
    OrderPaidEmail orderPaidEmail;

    @Autowired
    SchedulerClient scheduler;

    @Autowired
    ApplicationContext context;

    private String admin;

    @BeforeEach
    void setUp() {
        gateway.reset();
        jdbc.update("delete from admin_alerts");
        jdbc.update("delete from administrators");
        // each test has its own address: the in-memory sender is shared by the whole context
        admin = "admin-" + UUID.randomUUID() + "@example.com";
        jdbc.update("insert into administrators (email, password_hash) values (?, 'x')", admin);
    }

    private record Placed(UUID customerId, UUID productId, UUID orderId, String chargeId) {}

    /** A customer, a product and a pending Order with its charge, written straight to the database. */
    private Placed pending() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), PRICE);
        return pendingFor(customer, product);
    }

    private Placed pendingFor(UUID customer, UUID product) {
        UUID orderId = UUID.randomUUID();
        String chargeId = "pay_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        orders.insert(orderId, customer, product, PRICE, chargeId, now, now.plus(Duration.ofMinutes(30)));
        return new Placed(customer, product, orderId, chargeId);
    }

    private UUID storeEvent(String eventType, String chargeId) {
        String json = "{\"event\":\"" + eventType + "\",\"payment\":{\"id\":\"" + chargeId + "\"}}";
        return events.insertIfAbsent("evt_" + UUID.randomUUID(), eventType, json, OffsetDateTime.now())
                .orElseThrow();
    }

    private List<Map<String, Object>> alerts(String type) {
        return jdbc.queryForList(
                "select detail, order_id, payment_event_id, occurrences, resolved_at from admin_alerts"
                        + " where type = ? order by id",
                type);
    }

    private List<Map<String, Object>> alertsOfEvent(UUID eventId) {
        return jdbc.queryForList(
                "select type, detail, order_id, occurrences from admin_alerts where payment_event_id = ?", eventId);
    }

    private int alertRows() {
        return jdbc.queryForObject("select count(*) from admin_alerts", Integer.class);
    }

    private List<EmailMessage> alertMails() {
        return sender.messagesTo(admin).stream()
                .filter(m -> m.subject().startsWith(SUBJECT_PREFIX))
                .toList();
    }

    private HttpResponse<String> webhook(String loopback, Map<String, String> headers, String body) throws Exception {
        return AsaasWebhookTestSupport.post(port, loopback, headers, body, "application/json");
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    private PixCharge paidCharge(Placed placed) {
        return new PixCharge(
                placed.chargeId(),
                "RECEIVED",
                new BigDecimal("29.90"),
                "PIX",
                placed.orderId().toString(),
                false);
    }

    private int droppedTasks() {
        return jdbc.queryForObject(
                "select count(*) from scheduled_tasks where task_name = 'customer-password-changed-notice'",
                Integer.class);
    }

    @Test
    @DisplayName("NFR-009 / gate #47: evento que esgota as tentativas grava o alerta GAVE_UP com o evento e o Pedido")
    void shouldRecordTheGaveUpAlertWithTheEventAndTheOrder() throws Exception {
        Placed placed = pending();
        String providerEventId = uniqueId();
        gateway.failAt("READ_CHARGE", PixGatewayException.Kind.UNAVAILABLE);

        HttpResponse<String> response =
                webhook(LOOPBACK, AUTH, paymentEvent(providerEventId, "PAYMENT_RECEIVED", placed.chargeId()));
        assertThat(response.statusCode()).isEqualTo(200);

        UUID eventId = jdbc.queryForObject(
                "select id from payment_events where provider_event_id = ?", UUID.class, providerEventId);
        await(
                "the GAVE_UP result",
                () -> jdbc.queryForObject(
                                "select count(*) from payment_event_results where payment_event_id = ?"
                                        + " and result = 'GAVE_UP'",
                                Integer.class,
                                eventId)
                        == 1);

        // the alert is written in the same transaction as the result, so it exists as soon as the result does
        List<Map<String, Object>> rows = alerts("PAYMENT_EVENT_GAVE_UP");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("payment_event_id", eventId)
                .containsEntry("order_id", placed.orderId())
                .containsEntry("detail", null);
        assertThat(AdminAlertTestAccess.dispatch(context)).isEqualTo(1);
        assertThat(alertMails()).hasSize(1);
        assertThat(alertMails().getFirst().text())
                .contains(eventId.toString(), placed.orderId().toString());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PaymentResult.class)
    @DisplayName("NFR-009: resultado que precisa de revisão grava alerta; os demais não")
    void shouldRecordAnAlertOnlyForAResultThatNeedsReview(PaymentResult result) {
        Placed placed = pending();
        UUID eventId = storeEvent("PAYMENT_RECEIVED", placed.chargeId());
        PaymentEvents.Ref ref = events.findRef(eventId).orElseThrow();

        outcome.finish(eventId, ref, result, placed.orderId(), placed.chargeId());

        List<Map<String, Object>> rows = alertsOfEvent(eventId);
        if (result.needsReview() && result != PaymentResult.GAVE_UP) {
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst())
                    .containsEntry("type", "PAYMENT_EVENT_NEEDS_REVIEW")
                    .containsEntry("detail", result.name())
                    .containsEntry("order_id", placed.orderId());
        } else {
            assertThat(rows).isEmpty();
        }
    }

    /** Makes every write to {@code admin_alerts} fail with a trigger: no table rename in the shared database. */
    private void failAlertWrites() {
        jdbc.execute("create or replace function admin_alerts_test_fail() returns trigger language plpgsql as"
                + " $$ begin raise exception 'admin_alerts is down (test)'; end $$");
        jdbc.execute("create trigger admin_alerts_test_fail before insert or update on admin_alerts"
                + " for each row execute function admin_alerts_test_fail()");
    }

    private void allowAlertWrites() {
        jdbc.execute("drop trigger if exists admin_alerts_test_fail on admin_alerts");
        jdbc.execute("drop function if exists admin_alerts_test_fail()");
    }

    /** Delays every insert into {@code admin_alerts}, to widen the window between a task removal and its alert. */
    private void slowAlertWrites() {
        jdbc.execute("create or replace function admin_alerts_test_slow() returns trigger language plpgsql as"
                + " $$ begin perform pg_sleep(0.5); return new; end $$");
        jdbc.execute("create trigger admin_alerts_test_slow before insert on admin_alerts"
                + " for each row execute function admin_alerts_test_slow()");
    }

    private void unslowAlertWrites() {
        jdbc.execute("drop trigger if exists admin_alerts_test_slow on admin_alerts");
        jdbc.execute("drop function if exists admin_alerts_test_slow()");
    }

    @Test
    @DisplayName("NFR-009: compra duplicada grava o alerta na transação do pagamento")
    void shouldRecordTheDuplicatePurchaseAlertInThePaymentTransaction() {
        Placed first = pending();
        gateway.answerCharge(first.chargeId(), paidCharge(first));
        assertThat(confirmation.process(storeEvent("PAYMENT_RECEIVED", first.chargeId())))
                .isEqualTo(PaymentResult.PAID);
        Placed second = pendingFor(first.customerId(), first.productId());
        gateway.answerCharge(second.chargeId(), paidCharge(second));
        UUID eventId = storeEvent("PAYMENT_RECEIVED", second.chargeId());

        // the alert cannot be written: the whole payment is rolled back with it
        failAlertWrites();
        try {
            assertThatThrownBy(() -> confirmation.process(eventId)).isInstanceOf(RuntimeException.class);
        } finally {
            allowAlertWrites();
        }
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, second.orderId()))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                        "select count(*) from payment_event_results where payment_event_id = ?",
                        Integer.class,
                        eventId))
                .isZero();

        assertThat(confirmation.process(eventId)).isEqualTo(PaymentResult.DUPLICATE_PURCHASE);

        List<Map<String, Object>> rows = alertsOfEvent(eventId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("type", "PAYMENT_EVENT_NEEDS_REVIEW")
                .containsEntry("detail", "DUPLICATE_PURCHASE")
                .containsEntry("order_id", second.orderId());
    }

    @Test
    @DisplayName(
            "NFR-009 / regra do #29: e-mail de pagamento que desiste grava alerta; reenviado antes do despacho, nenhum e-mail sai")
    void shouldRecordTheGaveUpPaymentEmailAndWithdrawItWhenResent() {
        Placed placed = pending();
        assertThat(orders.markPaid(placed.orderId(), Instant.now())).isTrue();

        orderPaidEmail.giveUp(placed.orderId());

        List<Map<String, Object>> rows = alerts("ORDER_PAID_EMAIL_GAVE_UP");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("order_id", placed.orderId())
                .containsEntry("payment_event_id", null)
                .containsEntry("detail", null);

        // resent by the runbook before the dispatch ran: the alert is withdrawn and no alert e-mail goes out
        orderPaidEmail.send(placed.orderId());

        assertThat(alerts("ORDER_PAID_EMAIL_GAVE_UP").getFirst().get("resolved_at"))
                .isNotNull();
        assertThat(AdminAlertTestAccess.dispatch(context)).isZero();
        assertThat(alertMails()).isEmpty();

        // an e-mail that was already sent never alerts, even if the give-up runs again
        orderPaidEmail.giveUp(placed.orderId());
        assertThat(alerts("ORDER_PAID_EMAIL_GAVE_UP")).hasSize(1);
    }

    @Test
    @DisplayName(
            "NFR-009 / SEC-023: token errado de IP permitido grava TOKEN_INVALID; token certo de IP fora da lista grava IP_NOT_ALLOWED; os dois errados não gravam nada")
    void shouldRecordOnlyWhenExactlyOneWebhookCredentialMatches() throws Exception {
        assertThat(webhook("127.0.0.1", WRONG_TOKEN, "{}").statusCode()).isEqualTo(401);
        assertThat(alerts("WEBHOOK_REJECTED")).hasSize(1);
        assertThat(alerts("WEBHOOK_REJECTED").getFirst())
                .containsEntry("detail", "TOKEN_INVALID")
                .containsEntry("order_id", null)
                .containsEntry("payment_event_id", null);

        assertThat(webhook("127.0.0.21", AUTH, "{}").statusCode()).isEqualTo(401);
        assertThat(alerts("WEBHOOK_REJECTED")).hasSize(2);
        assertThat(alerts("WEBHOOK_REJECTED").get(1)).containsEntry("detail", "IP_NOT_ALLOWED");

        assertThat(webhook("127.0.0.22", WRONG_TOKEN, "{}").statusCode()).isEqualTo(401);
        assertThat(alertRows()).isEqualTo(2);
    }

    @Test
    @DisplayName("NFR-009: corpo inválido grava BODY_INVALID e corpo grande demais grava BODY_TOO_LARGE")
    void shouldRecordAnInvalidAndAnOversizedBody() throws Exception {
        assertThat(webhook(LOOPBACK, AUTH, "{\"id\": ").statusCode()).isEqualTo(400);
        String oversized = "{\"id\":\"" + "a".repeat(70_000) + "\"}";
        assertThat(webhook(LOOPBACK, AUTH, oversized).statusCode()).isEqualTo(413);

        assertThat(alerts("WEBHOOK_REJECTED"))
                .extracting(row -> row.get("detail"))
                .containsExactlyInAnyOrder("BODY_INVALID", "BODY_TOO_LARGE");
    }

    @Test
    @DisplayName("NFR-009: uma requisição recusada conta como uma ocorrência")
    void shouldCountARefusedRequestOnce() throws Exception {
        assertThat(webhook(LOOPBACK, WRONG_TOKEN, "{}").statusCode()).isEqualTo(401);
        assertThat(webhook(LOOPBACK, AUTH, "{\"id\": ").statusCode()).isEqualTo(400);

        List<Map<String, Object>> rows = alerts("WEBHOOK_REJECTED");
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("occurrences", 1));
    }

    @Test
    @DisplayName(
            "NFR-009: ataque de 300 webhooks recusados vira 1 linha e 1 e-mail; outros 300 na mesma janela não geram e-mail")
    void shouldTurnAnAttackIntoOneRowAndOneEmail() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .localAddress(InetAddress.getByName(LOOPBACK))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/webhooks/asaas"))
                .header("Content-Type", "application/json")
                .header(AsaasWebhookAccess.TOKEN_HEADER, "wrong-token")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        for (int i = 0; i < 300; i++) {
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding())
                            .statusCode())
                    .isEqualTo(401);
        }

        assertThat(alertRows()).isEqualTo(1);
        assertThat(alerts("WEBHOOK_REJECTED").getFirst()).containsEntry("occurrences", 300);
        assertThat(AdminAlertTestAccess.dispatch(context)).isEqualTo(1);
        assertThat(alertMails()).hasSize(1);
        assertThat(alertMails().getFirst().subject()).endsWith("(300)");

        for (int i = 0; i < 300; i++) {
            client.send(request, HttpResponse.BodyHandlers.discarding());
        }

        assertThat(alertRows()).isEqualTo(2);
        assertThat(AdminAlertTestAccess.dispatch(context)).isZero();
        assertThat(alertMails()).hasSize(1);
    }

    @Test
    @DisplayName("NFR-009: banco fora ao gravar o alerta não muda a resposta do webhook")
    void shouldKeepTheWebhookAnswerWhenTheAlertCannotBeWritten(CapturedOutput output) throws Exception {
        failAlertWrites();
        try {
            assertThat(webhook(LOOPBACK, WRONG_TOKEN, "{}").statusCode()).isEqualTo(401);
            assertThat(webhook("127.0.0.21", AUTH, "{}").statusCode()).isEqualTo(401);
            assertThat(webhook(LOOPBACK, AUTH, "{\"id\": ").statusCode()).isEqualTo(400);
            String oversized = "{\"id\":\"" + "a".repeat(70_000) + "\"}";
            assertThat(webhook(LOOPBACK, AUTH, oversized).statusCode()).isEqualTo(413);
        } finally {
            allowAlertWrites();
        }

        assertThat(output.getAll()).contains("Admin alert not recorded: type=WEBHOOK_REJECTED reason=");
        assertThat(alertRows()).isZero();
    }

    @Test
    @DisplayName("ADR-0005: tarefa de e-mail descartada grava TASK_GAVE_UP com o nome da tarefa")
    void shouldRecordTheNameOfADroppedEmailTask() throws Exception {
        UUID customerId = OrderTestSupport.insertCustomer(jdbc);
        String email = jdbc.queryForObject("select email from customers where id = ?", String.class, customerId);
        sender.failAllSendsTo(email);

        // the alert is written after the task row is removed: a slow write keeps that window open
        slowAlertWrites();
        try {
            scheduler.schedule(
                    new TaskInstance<>(
                            "customer-password-changed-notice",
                            UUID.randomUUID().toString(),
                            customerId),
                    Instant.now());

            // wait for the alert itself: the task row is removed before the alert is written
            await("the TASK_GAVE_UP alert", () -> !alerts("TASK_GAVE_UP").isEmpty());

            // app.email.max-attempts is 5 in this context
            assertThat(droppedTasks()).isZero();
            assertThat(sender.attemptsTo(email)).isGreaterThanOrEqualTo(5);
            List<Map<String, Object>> rows = alerts("TASK_GAVE_UP");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst())
                    .containsEntry("detail", "customer-password-changed-notice")
                    .containsEntry("order_id", null)
                    .containsEntry("payment_event_id", null);
        } finally {
            unslowAlertWrites();
        }
    }
}
