package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.TOKEN;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.paymentEvent;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.email.EmailMessage;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * FR-024: the payment confirmation e-mail, from the webhook to the recorded message. Same Spring context as
 * PaymentEventProcessingTest (same imports and properties), so the scheduler tasks really run.
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
class OrderPaidEmailTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    /** The context uses app.email.max-attempts from application.yml. */
    private static final int MAX_ATTEMPTS = 5;

    private static final Map<String, String> AUTH = Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SwitchablePixGateway gateway;

    @Autowired
    RecordingEmailSender sender;

    @Autowired
    Orders orders;

    @Autowired
    OrderPaidEmail orderPaidEmail;

    @BeforeEach
    void setUp() {
        gateway.reset();
    }

    private record Placed(UUID orderId, String chargeId, String email, String title) {}

    private Placed placeOrder() throws Exception {
        OrderHttp.Customer customer = OrderHttp.newCustomer(port, jdbc, encoder);
        String title = "Guia 100% " + UUID.randomUUID();
        UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, title, 2990);
        HttpResponse<String> created = customer.order(productId, "52998224725");
        assertThat(created.statusCode()).isEqualTo(201);
        UUID orderId = UUID.fromString(OrderHttp.json(created, "$.id"));
        String chargeId =
                jdbc.queryForObject("select provider_payment_id from orders where id = ?", String.class, orderId);
        gateway.reset();
        return new Placed(orderId, chargeId, customer.email, title);
    }

    private void pay(Placed placed, String eventId) throws Exception {
        HttpResponse<String> response = AsaasWebhookTestSupport.post(
                port,
                "127.0.0.1",
                AUTH,
                paymentEvent(eventId, "PAYMENT_RECEIVED", placed.chargeId()),
                "application/json");
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private EmailMessage nextMessageTo(String to) throws InterruptedException {
        return sender.awaitMessageTo(to, WAIT).orElseThrow(() -> new AssertionError("no e-mail arrived"));
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private int accesses(UUID orderId) {
        return jdbc.queryForObject("select count(*) from accesses where order_id = ?", Integer.class, orderId);
    }

    private boolean columnFilled(UUID orderId, String column) {
        return jdbc.queryForObject(
                "select " + column + " is not null from orders where id = ?", Boolean.class, orderId);
    }

    private int emailTasks(UUID orderId) {
        return jdbc.queryForObject(
                "select count(*) from scheduled_tasks where task_name = 'order-paid-email' and task_instance = ?",
                Integer.class,
                orderId.toString());
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

    private static void assertNoPersonalData(CapturedOutput output, Placed placed) {
        assertThat(output.getAll())
                .doesNotContain(placed.email())
                .doesNotContain(placed.title())
                .doesNotContain("29,90")
                .doesNotContain("29.90");
    }

    @Test
    @DisplayName("FR-024: o Cliente recebe o e-mail de pagamento confirmado")
    void shouldSendThePaymentConfirmationEmail(CapturedOutput output) throws Exception {
        Placed placed = placeOrder();

        pay(placed, uniqueId());

        EmailMessage message = nextMessageTo(placed.email());
        assertThat(message.subject()).isEqualTo("Seu pagamento na SapiensIA foi confirmado");
        assertThat(message.text()).isEqualTo(OrderPaidEmail.body(placed.title(), 2990, "http://localhost:5173"));
        assertThat(message.text())
                .contains("Produto: " + placed.title())
                .contains("Valor: R$ 29,90")
                .contains("http://localhost:5173/biblioteca")
                .doesNotContain(placed.chargeId())
                .doesNotContain(placed.orderId().toString());
        assertThat(message.idempotencyKey()).isEqualTo("order-paid-email/" + placed.orderId());
        await("payment_email_sent_at", () -> columnFilled(placed.orderId(), "payment_email_sent_at"));
        assertThat(columnFilled(placed.orderId(), "payment_email_failed_at")).isFalse();
        await("the task to finish", () -> emailTasks(placed.orderId()) == 0);
        assertThat(output.getAll()).contains("Order paid e-mail sent: orderId=" + placed.orderId());
        assertNoPersonalData(output, placed);
    }

    @Test
    @DisplayName("FR-024: aviso duplicado do Asaas gera um e-mail só")
    void shouldSendOneEmailForADuplicatedProviderNotice() throws Exception {
        Placed placed = placeOrder();
        String eventId = uniqueId();

        pay(placed, eventId);
        pay(placed, eventId);
        pay(placed, uniqueId());

        nextMessageTo(placed.email());
        await("the task to finish", () -> emailTasks(placed.orderId()) == 0);
        assertThat(sender.awaitMessageTo(placed.email(), Duration.ofSeconds(3))).isEmpty();
        assertThat(sender.messagesTo(placed.email())).hasSize(1);
    }

    @Test
    @DisplayName("FR-024: tarefa executada de novo não envia outro e-mail")
    void shouldNotSendAgainWhenTheTaskRunsAgain() throws Exception {
        Placed placed = placeOrder();
        pay(placed, uniqueId());
        nextMessageTo(placed.email());
        await("payment_email_sent_at", () -> columnFilled(placed.orderId(), "payment_email_sent_at"));

        orderPaidEmail.send(placed.orderId());

        assertThat(sender.awaitMessageTo(placed.email(), Duration.ofSeconds(1))).isEmpty();
        assertThat(sender.messagesTo(placed.email())).hasSize(1);
    }

    @Test
    @DisplayName("FR-024: falha no envio não atrasa nem desfaz o Acesso")
    void shouldKeepTheAccessWhileTheEmailFails() throws Exception {
        Placed placed = placeOrder();
        sender.failNextSendTo(placed.email());

        pay(placed, uniqueId());

        await("the failed attempt", () -> !sender.hasPendingFailureFor(placed.email()));
        assertThat(status(placed.orderId())).isEqualTo("PAID");
        assertThat(accesses(placed.orderId())).isEqualTo(1);
        assertThat(columnFilled(placed.orderId(), "payment_email_sent_at")).isFalse();
        assertThat(sender.messagesTo(placed.email())).isEmpty();

        nextMessageTo(placed.email());
        await("payment_email_sent_at", () -> columnFilled(placed.orderId(), "payment_email_sent_at"));
        assertThat(sender.messagesTo(placed.email())).hasSize(1);
        assertThat(status(placed.orderId())).isEqualTo("PAID");
        assertThat(accesses(placed.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-024: quando o envio falha de vez, o Acesso fica e a falha é registrada")
    void shouldRecordTheFailureWhenTheEmailGivesUp(CapturedOutput output) throws Exception {
        Placed placed = placeOrder();
        sender.failAllSendsTo(placed.email());

        pay(placed, uniqueId());

        await("payment_email_failed_at", () -> columnFilled(placed.orderId(), "payment_email_failed_at"));
        await("the task to be removed", () -> emailTasks(placed.orderId()) == 0);
        assertThat(columnFilled(placed.orderId(), "payment_email_sent_at")).isFalse();
        assertThat(status(placed.orderId())).isEqualTo("PAID");
        assertThat(accesses(placed.orderId())).isEqualTo(1);
        assertThat(sender.messagesTo(placed.email())).isEmpty();
        // app.email.max-attempts is 5 in this context: exactly 5 sends, then the give-up
        assertThat(sender.attemptsTo(placed.email())).isEqualTo(MAX_ATTEMPTS);
        assertThat(output.getAll()).contains("Order paid e-mail gave up: orderId=" + placed.orderId());
        assertNoPersonalData(output, placed);
    }

    @Test
    @DisplayName("FR-024: o log do e-mail de pagamento não traz dado pessoal")
    void shouldLogNoPersonalDataAboutThePaymentEmail(CapturedOutput output) throws Exception {
        Placed retried = placeOrder();
        sender.failNextSendTo(retried.email());
        pay(retried, uniqueId());
        nextMessageTo(retried.email());
        await("payment_email_sent_at", () -> columnFilled(retried.orderId(), "payment_email_sent_at"));
        orderPaidEmail.send(retried.orderId());

        assertThat(output.getAll())
                .contains("Order paid e-mail sent: orderId=" + retried.orderId())
                .contains("Order paid e-mail skipped: orderId=" + retried.orderId() + " reason=ALREADY_SENT");
        assertNoPersonalData(output, retried);
    }

    @Test
    @DisplayName("FR-024: Orders marca o envio uma vez e a falha só antes do envio")
    void shouldMarkTheEmailOnceAndFailOnlyBeforeTheSend() throws Exception {
        Placed placed = placeOrder();
        pay(placed, uniqueId());
        nextMessageTo(placed.email());
        await("payment_email_sent_at", () -> columnFilled(placed.orderId(), "payment_email_sent_at"));

        assertThat(orders.markPaymentEmailSent(placed.orderId(), Instant.now())).isFalse();
        orders.markPaymentEmailFailed(placed.orderId(), Instant.now());

        assertThat(columnFilled(placed.orderId(), "payment_email_failed_at")).isFalse();
    }
}
