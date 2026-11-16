package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.TOKEN;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.paymentEvent;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** SEC-030: the reconciliation and the webhook of the same charge pay the Order once. */
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
// Same annotations and properties as RefundProcessingTest, so the cached context is reused.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=payment-event-processing",
            "app.login-limit.ip-max-attempts=1000",
            "db-scheduler.polling-interval=PT0.2S",
            "app.orders.payment-processing.retry-delay=PT0.5S",
            "app.orders.payment-processing.max-attempts=5"
        })
class ReconciliationConcurrencyTest {

    private static final int PRICE = 2990;
    private static final Map<String, String> AUTH = Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SwitchablePixGateway gateway;

    @Autowired
    Reconciliation reconciliation;

    @Autowired
    Orders orders;

    @Autowired
    RecordingEmailSender sender;

    @BeforeEach
    void setUp() {
        gateway.reset();
    }

    private int results(String providerEventId, String result) {
        return jdbc.queryForObject(
                "select count(*) from payment_event_results r join payment_events e on e.id = r.payment_event_id"
                        + " where e.provider_event_id = ? and r.result = ?",
                Integer.class,
                providerEventId,
                result);
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

    @Test
    @DisplayName("SEC-030: conciliação junto do webhook da mesma cobrança concede um Acesso e manda um e-mail")
    void shouldGrantOneAccessWhenReconciliationRunsWhileTheWebhookIsBeingProcessed() throws Exception {
        String email = "cliente-" + UUID.randomUUID() + "@example.com";
        UUID customer = jdbc.queryForObject(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, 'x', now()) returning id",
                UUID.class,
                email);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), PRICE);
        UUID orderId = UUID.randomUUID();
        String chargeId = "pay_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        orders.insert(orderId, customer, product, PRICE, chargeId, now, now.plus(Duration.ofMinutes(30)));
        PixCharge paid = new PixCharge(chargeId, "RECEIVED", new BigDecimal("29.90"), "PIX", orderId.toString(), false);
        gateway.answerCharge(chargeId, paid);
        gateway.answerList(List.of(paid));
        String webhookEvent = uniqueId();
        String reconciliationEvent = "reconciliation:PAYMENT_RECEIVED:" + chargeId;
        CountDownLatch latch = new CountDownLatch(1);
        gateway.holdNextRead(latch);
        try {
            assertThat(AsaasWebhookTestSupport.post(
                                    port,
                                    "127.0.0.1",
                                    AUTH,
                                    paymentEvent(webhookEvent, "PAYMENT_RECEIVED", chargeId),
                                    "application/json")
                            .statusCode())
                    .isEqualTo(200);
            await("the webhook task to reach the provider", () -> gateway.count("READ_CHARGE") >= 1);

            reconciliation.run();
            await("the reconciliation event to be PAID", () -> results(reconciliationEvent, "PAID") == 1);
        } finally {
            latch.countDown();
        }

        await("the webhook event to be ALREADY_PAID", () -> results(webhookEvent, "ALREADY_PAID") == 1);
        assertThat(jdbc.queryForObject("select count(*) from accesses where order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(sender.awaitMessageTo(email, Duration.ofSeconds(15))).isPresent();
        assertThat(sender.messagesTo(email)).hasSize(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from reconciliation_divergences where charge_id = ?", Integer.class, chargeId))
                .isZero();
    }
}
