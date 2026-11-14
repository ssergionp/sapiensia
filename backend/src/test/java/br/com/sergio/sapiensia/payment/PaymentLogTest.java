package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.TOKEN;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
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

/** Guard: processing a payment event never logs the body, the value, personal data, the token or the Pix code. */
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=payment-log",
            "app.asaas.webhook.allowed-ips=127.0.0.1",
            "app.login-limit.ip-max-attempts=1000",
            "db-scheduler.polling-interval=PT0.2S",
            "app.orders.payment-processing.retry-delay=PT0.3S",
            "app.orders.payment-processing.max-attempts=2",
            // the noisiest levels that could print a request, a body, an answer or a SQL parameter
            "logging.level.org.springframework.security=TRACE",
            "logging.level.org.springframework.web=DEBUG",
            "logging.level.br.com.sergio.sapiensia=TRACE"
        })
class PaymentLogTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final Map<String, String> AUTH = Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN);
    private static final String CPF = "52998224725";

    /**
     * The value in the forms it could take next to a field name. A bare "2990" is not checked alone: it can appear
     * inside a UUID or a timestamp, so only "value/amount/price/cents" followed by 2990 or 29.9, or "29.90", count.
     */
    private static final Pattern VALUE_FORMS =
            Pattern.compile("(?i)(value|amount|price|cents)\\W{0,4}(2990|29\\.9)(?![0-9])|29\\.90");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SwitchablePixGateway gateway;

    @BeforeEach
    void setUp() {
        gateway.reset();
    }

    private record Placed(UUID orderId, String chargeId, String email) {}

    private Placed placeOrder() throws Exception {
        OrderHttp.Customer customer = OrderHttp.newCustomer(port, jdbc, encoder);
        UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), 2990);
        HttpResponse<String> created = customer.order(productId, CPF);
        assertThat(created.statusCode()).isEqualTo(201);
        UUID orderId = UUID.fromString(OrderHttp.json(created, "$.id"));
        String chargeId =
                jdbc.queryForObject("select provider_payment_id from orders where id = ?", String.class, orderId);
        return new Placed(orderId, chargeId, customer.email);
    }

    private void send(String eventId, String chargeId, String marker) throws Exception {
        String body = """
                {"id": "%s", "event": "PAYMENT_RECEIVED", "dateCreated": "2026-11-11 10:00:00",
                 "payment": {"object": "payment", "id": "%s", "customer": "cus_%s", "value": 29.9,
                             "description": "desc-%s", "cpfCnpj": "98765432100", "billingType": "PIX",
                             "status": "RECEIVED"}}""".formatted(eventId, chargeId, marker, marker);
        assertThat(AsaasWebhookTestSupport.post(port, LOOPBACK, AUTH, body, "application/json")
                        .statusCode())
                .isEqualTo(200);
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private int results(String eventId) {
        return jdbc.queryForObject(
                "select count(*) from payment_event_results r join payment_events e on e.id = r.payment_event_id"
                        + " where e.provider_event_id = ?",
                Integer.class,
                eventId);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    @Test
    @DisplayName("ADR-0006, SEC-033: shouldNeverLogThePayloadTheValueOrPersonalDataWhilePaying")
    void shouldNeverLogThePayloadTheValueOrPersonalDataWhilePaying(CapturedOutput output) throws Exception {
        String marker = "MARKER" + UUID.randomUUID().toString().replace("-", "");
        String refMarker = "REFMARKER" + UUID.randomUUID().toString().replace("-", "");

        // (a) the provider answers with another reference: the REFERENCE_MISMATCH case
        Placed mismatch = placeOrder();
        gateway.reset();
        gateway.answerCharge(
                mismatch.chargeId(),
                new PixCharge(mismatch.chargeId(), "RECEIVED", new BigDecimal("29.90"), "PIX", refMarker, false));
        String mismatchEvent = uniqueId();
        send(mismatchEvent, mismatch.chargeId(), marker);
        await("the mismatch result", () -> results(mismatchEvent) == 1);

        // (b) the happy path: the Order is paid
        Placed happy = placeOrder();
        gateway.reset();
        String happyEvent = uniqueId();
        send(happyEvent, happy.chargeId(), marker);
        await("the paid Order", () -> "PAID".equals(status(happy.orderId())));

        // (c) a give-up: the provider stays unavailable until the attempts end
        Placed giveUp = placeOrder();
        gateway.reset();
        gateway.failAt("READ_CHARGE", PixGatewayException.Kind.UNAVAILABLE);
        String giveUpEvent = uniqueId();
        send(giveUpEvent, giveUp.chargeId(), marker);
        await("the give-up result", () -> results(giveUpEvent) >= 1);
        gateway.failAt("READ_CHARGE", null);

        String logs = output.getAll();
        assertThat(logs).as("capture works: the processing logged").contains("Payment event processed");
        assertThat(logs)
                .doesNotContain(marker)
                .doesNotContain(refMarker)
                .doesNotContain("98765432100")
                .doesNotContain(CPF)
                .doesNotContain(TOKEN)
                .doesNotContain(mismatch.email())
                .doesNotContain(happy.email())
                .doesNotContain(giveUp.email())
                .doesNotContain("PIX-FICTICIO-NAO-PAGAVEL")
                .doesNotContain("copyPaste");
        assertThat(VALUE_FORMS.matcher(logs).find())
                .as("the value never appears in a log line")
                .isFalse();
    }
}
