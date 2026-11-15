package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.TOKEN;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.alert.AdminAlertTestAccess;
import br.com.sergio.sapiensia.email.EmailMessage;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-0006 / SEC-036: the alert e-mail and the alert log lines never carry personal or secret data. All five alert
 * types are raised through their real origins with planted values. It lives in the payment package (same Spring
 * context as AdminAlertOriginsTest) because the payment beans are package-private.
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
class AdminAlertPrivacyTest {

    private static final int PRICE = 2990;
    private static final String SUBJECT_PREFIX = "Alerta SapiensIA: ";
    private static final String CPF = "52998224725";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String OTHER_IP = "127.0.0.21";

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
    OrderPaidEmail orderPaidEmail;

    @Autowired
    SchedulerClient scheduler;

    @Autowired
    ApplicationContext context;

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

    private record Placed(UUID orderId, String chargeId) {}

    private Placed pendingFor(UUID customer) {
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), PRICE);
        UUID orderId = UUID.randomUUID();
        String chargeId = "pay_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        orders.insert(orderId, customer, product, PRICE, chargeId, now, now.plus(Duration.ofMinutes(30)));
        return new Placed(orderId, chargeId);
    }

    /** A payment event that also carries the planted CPF and marker, as free text from the provider could. */
    private static String eventBody(String providerEventId, String chargeId, String marker) {
        return "{\"id\":\"" + providerEventId + "\",\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"" + chargeId
                + "\",\"cpfCnpj\":\"" + CPF + "\",\"description\":\"" + marker + "\"}}";
    }

    @Test
    @DisplayName(
            "ADR-0006 / SEC-036: o e-mail e o log dos alertas não trazem CPF, e-mail de Cliente, token, senha, corpo do webhook nem IP")
    void shouldKeepPersonalAndSecretDataOutOfTheAlertEmailAndLog(CapturedOutput output) throws Exception {
        gateway.reset();
        jdbc.update("delete from admin_alerts");
        jdbc.update("delete from administrators");
        String admin = "admin-" + UUID.randomUUID() + "@example.com";
        jdbc.update("insert into administrators (email, password_hash) values (?, 'x')", admin);

        // planted values, built at runtime (never secret-looking literals)
        String customerEmail = "known-customer-" + UUID.randomUUID() + "@example.com";
        String marker = "marker-" + UUID.randomUUID();
        String wrongToken = "wrong-" + UUID.randomUUID();
        UUID customer = jdbc.queryForObject(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, 'x', now()) returning id",
                UUID.class,
                customerEmail);

        // PAYMENT_EVENT_GAVE_UP: the provider stays down until the attempts run out
        Placed unreachable = pendingFor(customer);
        gateway.failAt("READ_CHARGE", PixGatewayException.Kind.UNAVAILABLE);
        assertThat(AsaasWebhookTestSupport.post(
                                port,
                                LOOPBACK,
                                Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN),
                                eventBody(uniqueId(), unreachable.chargeId(), marker),
                                "application/json")
                        .statusCode())
                .isEqualTo(200);
        await(
                "the GAVE_UP alert",
                () -> jdbc.queryForObject(
                                "select count(*) from admin_alerts where type = 'PAYMENT_EVENT_GAVE_UP'", Integer.class)
                        == 1);
        gateway.reset();

        // PAYMENT_EVENT_NEEDS_REVIEW
        Placed review = pendingFor(customer);
        UUID eventId = events.insertIfAbsent(
                        uniqueId(),
                        "PAYMENT_RECEIVED",
                        eventBody(uniqueId(), review.chargeId(), marker),
                        OffsetDateTime.now())
                .orElseThrow();
        outcome.finish(
                eventId,
                events.findRef(eventId).orElseThrow(),
                PaymentResult.MED_BLOCKED,
                review.orderId(),
                review.chargeId());

        // ORDER_PAID_EMAIL_GAVE_UP
        Placed paid = pendingFor(customer);
        assertThat(orders.markPaid(paid.orderId(), Instant.now())).isTrue();
        orderPaidEmail.giveUp(paid.orderId());

        // WEBHOOK_REJECTED: token refused from an allowed IP, right token from another IP, unreadable body
        assertThat(AsaasWebhookTestSupport.post(
                                port,
                                LOOPBACK,
                                Map.of(AsaasWebhookAccess.TOKEN_HEADER, wrongToken),
                                eventBody(uniqueId(), "pay_x", marker),
                                "application/json")
                        .statusCode())
                .isEqualTo(401);
        assertThat(AsaasWebhookTestSupport.post(
                                port,
                                OTHER_IP,
                                Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN),
                                eventBody(uniqueId(), "pay_x", marker),
                                "application/json")
                        .statusCode())
                .isEqualTo(401);
        assertThat(AsaasWebhookTestSupport.post(
                                port,
                                LOOPBACK,
                                Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN),
                                "{\"cpfCnpj\":\"" + CPF + "\",\"description\":\"" + marker + "\"",
                                "application/json")
                        .statusCode())
                .isEqualTo(400);

        // TASK_GAVE_UP: the e-mail task of the known Customer fails on every attempt
        sender.failAllSendsTo(customerEmail);
        scheduler.schedule(
                new TaskInstance<>(
                        "customer-password-changed-notice", UUID.randomUUID().toString(), customer),
                Instant.now());
        await(
                "the dropped task",
                () -> sender.attemptsTo(customerEmail) >= 5
                        && jdbc.queryForObject(
                                        "select count(*) from scheduled_tasks"
                                                + " where task_name = 'customer-password-changed-notice'",
                                        Integer.class)
                                == 0);

        assertThat(jdbc.queryForList("select distinct type from admin_alerts"))
                .extracting(row -> row.get("type"))
                .containsExactlyInAnyOrder(
                        "PAYMENT_EVENT_GAVE_UP",
                        "PAYMENT_EVENT_NEEDS_REVIEW",
                        "ORDER_PAID_EMAIL_GAVE_UP",
                        "WEBHOOK_REJECTED",
                        "TASK_GAVE_UP");
        assertThat(AdminAlertTestAccess.dispatch(context)).isEqualTo(5);

        List<String> forbidden = List.of(customerEmail, CPF, marker, TOKEN, wrongToken, LOOPBACK, OTHER_IP);
        List<EmailMessage> mails = sender.messagesTo(admin).stream()
                .filter(m -> m.subject().startsWith(SUBJECT_PREFIX))
                .toList();
        assertThat(mails).hasSize(5);
        for (EmailMessage mail : mails) {
            assertThat(mail.subject() + "\n" + mail.text()).doesNotContain(forbidden);
        }

        List<String> alertLines = output.getAll()
                .lines()
                .filter(line -> line.contains("Admin alert"))
                .toList();
        assertThat(alertLines).isNotEmpty();
        for (String line : alertLines) {
            assertThat(line).doesNotContain(forbidden);
        }
    }
}
