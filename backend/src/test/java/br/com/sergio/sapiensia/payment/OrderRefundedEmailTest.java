package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.email.EmailMessage;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
 * FR-023: the refund e-mail, from the scheduled task to the recorded message. Same Spring context as
 * OrderPaidEmailTest (same imports and properties). The task is scheduled here by hand: scheduling it from the refund
 * is the next task of #131.
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
class OrderRefundedEmailTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    /** The context uses app.email.max-attempts from application.yml. */
    private static final int MAX_ATTEMPTS = 5;

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
    OrderRefundedEmail orderRefundedEmail;

    @Autowired
    SchedulerClient scheduler;

    @BeforeEach
    void setUp() {
        gateway.reset();
    }

    private record Placed(UUID orderId, String email, String title) {}

    private Placed placeOrder(boolean refunded) throws Exception {
        OrderHttp.Customer customer = OrderHttp.newCustomer(port, jdbc, encoder);
        String title = "Guia 100% " + UUID.randomUUID();
        UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, title, 2990);
        HttpResponse<String> created = customer.order(productId, "52998224725");
        assertThat(created.statusCode()).isEqualTo(201);
        UUID orderId = UUID.fromString(OrderHttp.json(created, "$.id"));
        gateway.reset();
        if (refunded) {
            assertThat(orders.markRefunded(orderId, Instant.now())).isTrue();
        }
        return new Placed(orderId, customer.email, title);
    }

    private void schedule(UUID orderId) {
        scheduler.schedule(new TaskInstance<Void>(OrderRefundedEmailTasks.SEND, orderId.toString()), Instant.now());
    }

    private EmailMessage nextMessageTo(String to) throws InterruptedException {
        return sender.awaitMessageTo(to, WAIT).orElseThrow(() -> new AssertionError("no e-mail arrived"));
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private boolean columnFilled(UUID orderId, String column) {
        return jdbc.queryForObject(
                "select " + column + " is not null from orders where id = ?", Boolean.class, orderId);
    }

    private int emailTasks(UUID orderId) {
        return jdbc.queryForObject(
                "select count(*) from scheduled_tasks where task_name = 'order-refunded-email' and task_instance = ?",
                Integer.class,
                orderId.toString());
    }

    private int alerts(UUID orderId) {
        return jdbc.queryForObject(
                "select count(*) from admin_alerts where type = 'ORDER_REFUNDED_EMAIL_GAVE_UP' and order_id = ?",
                Integer.class,
                orderId);
    }

    private boolean alertResolved(UUID orderId) {
        return jdbc.queryForObject(
                "select resolved_at is not null from admin_alerts"
                        + " where type = 'ORDER_REFUNDED_EMAIL_GAVE_UP' and order_id = ?",
                Boolean.class,
                orderId);
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
    @DisplayName("FR-023: o Cliente recebe o e-mail de reembolso, uma vez")
    void shouldSendTheRefundEmailOnce(CapturedOutput output) throws Exception {
        Placed placed = placeOrder(true);

        schedule(placed.orderId());

        EmailMessage message = nextMessageTo(placed.email());
        assertThat(message.subject()).isEqualTo("Seu Pedido na SapiensIA foi reembolsado");
        assertThat(message.text()).isEqualTo(OrderRefundedEmail.body(placed.title(), 2990));
        assertThat(message.text()).contains("Produto: " + placed.title()).contains("Valor: R$ 29,90");
        assertThat(message.idempotencyKey()).isEqualTo("order-refunded-email/" + placed.orderId());
        await("refund_email_sent_at", () -> columnFilled(placed.orderId(), "refund_email_sent_at"));
        assertThat(columnFilled(placed.orderId(), "refund_email_failed_at")).isFalse();
        await("the task to finish", () -> emailTasks(placed.orderId()) == 0);
        assertThat(status(placed.orderId())).isEqualTo("REFUNDED");
        assertThat(output.getAll()).contains("Order refunded e-mail sent: orderId=" + placed.orderId());
        assertNoPersonalData(output, placed);

        // the task running again (or a second refund notice) sends nothing more
        orderRefundedEmail.send(placed.orderId());

        assertThat(sender.awaitMessageTo(placed.email(), Duration.ofSeconds(1))).isEmpty();
        assertThat(sender.messagesTo(placed.email())).hasSize(1);
        assertThat(output.getAll())
                .contains("Order refunded e-mail skipped: orderId=" + placed.orderId() + " reason=ALREADY_SENT");
    }

    @Test
    @DisplayName("FR-023: Pedido que não foi reembolsado não recebe o e-mail")
    void shouldNotSendForAnOrderThatIsNotRefunded(CapturedOutput output) throws Exception {
        Placed placed = placeOrder(false);

        orderRefundedEmail.send(placed.orderId());

        assertThat(sender.messagesTo(placed.email())).isEmpty();
        assertThat(columnFilled(placed.orderId(), "refund_email_sent_at")).isFalse();
        assertThat(output.getAll())
                .contains("Order refunded e-mail skipped: orderId=" + placed.orderId() + " reason=NOT_REFUNDED");
    }

    @Test
    @DisplayName("FR-023: quando o envio falha de vez, a falha é registrada e o Administrador é avisado")
    void shouldRecordTheFailureAndAlertWhenTheEmailGivesUp(CapturedOutput output) throws Exception {
        Placed placed = placeOrder(true);
        sender.failAllSendsTo(placed.email());

        schedule(placed.orderId());

        await("refund_email_failed_at", () -> columnFilled(placed.orderId(), "refund_email_failed_at"));
        await("the task to be removed", () -> emailTasks(placed.orderId()) == 0);
        assertThat(columnFilled(placed.orderId(), "refund_email_sent_at")).isFalse();
        assertThat(status(placed.orderId())).isEqualTo("REFUNDED");
        assertThat(sender.messagesTo(placed.email())).isEmpty();
        assertThat(sender.attemptsTo(placed.email())).isEqualTo(MAX_ATTEMPTS);
        assertThat(alerts(placed.orderId())).isEqualTo(1);
        assertThat(output.getAll()).contains("Order refunded e-mail gave up: orderId=" + placed.orderId());
        assertNoPersonalData(output, placed);
    }

    @Test
    @DisplayName("FR-023: envio que dá certo depois da desistência resolve o alerta")
    void shouldResolveTheAlertWhenTheEmailIsSentLater() throws Exception {
        Placed placed = placeOrder(true);
        orderRefundedEmail.giveUp(placed.orderId());
        assertThat(alerts(placed.orderId())).isEqualTo(1);
        assertThat(alertResolved(placed.orderId())).isFalse();

        orderRefundedEmail.send(placed.orderId());

        assertThat(sender.messagesTo(placed.email())).hasSize(1);
        assertThat(alertResolved(placed.orderId())).isTrue();
        // an e-mail that was already sent never alerts, even if the give-up runs again
        orderRefundedEmail.giveUp(placed.orderId());
        assertThat(alerts(placed.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-023: Orders marca o envio uma vez e a falha só antes do envio")
    void shouldMarkTheEmailOnceAndFailOnlyBeforeTheSend() throws Exception {
        Placed placed = placeOrder(true);
        assertThat(orders.refundEmailPending(placed.orderId())).isTrue();

        assertThat(orders.markRefundEmailSent(placed.orderId(), Instant.now())).isTrue();

        assertThat(orders.markRefundEmailSent(placed.orderId(), Instant.now())).isFalse();
        assertThat(orders.refundEmailPending(placed.orderId())).isFalse();
        assertThat(orders.markRefundEmailFailed(placed.orderId(), Instant.now()))
                .isFalse();
        assertThat(columnFilled(placed.orderId(), "refund_email_failed_at")).isFalse();
    }
}
