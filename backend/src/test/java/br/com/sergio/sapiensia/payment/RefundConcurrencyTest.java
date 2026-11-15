package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** SEC-031: under races a refund cancels once, and a payment and a refund together always end REFUNDED. */
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
// Same annotations and properties as PaymentConcurrencyTest, so the cached context is reused.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=payment-concurrency",
            "app.login-limit.ip-max-attempts=1000",
            "db-scheduler.polling-interval=PT0.2S"
        })
class RefundConcurrencyTest {

    private static final int ROUNDS = 20;
    private static final int PRICE = 2990;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PaymentConfirmation confirmation;

    @Autowired
    RefundConfirmation refund;

    @Autowired
    Orders orders;

    @Autowired
    Accesses accesses;

    @Autowired
    PaymentEvents events;

    @Autowired
    SwitchablePixGateway gateway;

    @BeforeEach
    void setUp() {
        gateway.reset();
    }

    /** Releases all the actions at the same moment and returns their results; each waits at most 30 s. */
    private static <T> List<T> together(List<Callable<T>> actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        try {
            CountDownLatch ready = new CountDownLatch(actions.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> action : actions) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return action.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String newChargeId() {
        return "pay_" + UUID.randomUUID().toString().replace("-", "");
    }

    private UUID insertOrder(UUID customer, UUID product, String chargeId) {
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();
        orders.insert(
                orderId, customer, product, PRICE, chargeId, now.minusSeconds(60), now.plus(Duration.ofMinutes(30)));
        return orderId;
    }

    private static PixCharge charge(String chargeId, UUID orderId, String state) {
        return new PixCharge(chargeId, state, new BigDecimal("29.90"), "PIX", orderId.toString(), false);
    }

    private UUID storeEvent(String chargeId, String type) {
        String json = "{\"event\":\"" + type + "\",\"payment\":{\"id\":\"" + chargeId + "\"}}";
        String evt = "evt_" + UUID.randomUUID().toString().replace("-", "");
        return events.insertIfAbsent(evt, type, json, OffsetDateTime.now()).orElseThrow();
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private long audits(String action, UUID orderId) {
        return jdbc.queryForObject(
                "select count(*) from audit_events where action = ? and object_id = ?", Long.class, action, orderId);
    }

    private long activeAccesses(UUID orderId) {
        return jdbc.queryForObject(
                "select count(*) from accesses where order_id = ? and cancelled_at is null", Long.class, orderId);
    }

    @Test
    @DisplayName("SEC-031: dois avisos de estorno simultâneos cancelam uma vez só")
    void shouldCancelOnceWhenTwoRefundNoticesAreProcessedTogether() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID customer = OrderTestSupport.insertCustomer(jdbc);
            UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), PRICE);
            String chargeId = newChargeId();
            UUID orderId = insertOrder(customer, product, chargeId);
            Instant now = Instant.now();
            assertThat(orders.markPaid(orderId, now)).isTrue();
            assertThat(accesses.grant(customer, product, orderId, now)).isTrue();
            gateway.answerCharge(chargeId, charge(chargeId, orderId, "REFUNDED"));
            UUID first = storeEvent(chargeId, "PAYMENT_REFUNDED");
            UUID second = storeEvent(chargeId, "PAYMENT_REFUNDED");

            List<PaymentResult> results = together(List.of(() -> refund.process(first), () -> refund.process(second)));

            assertThat(results)
                    .as("round %d", round)
                    .containsExactlyInAnyOrder(PaymentResult.REFUNDED, PaymentResult.ALREADY_REFUNDED);
            assertThat(status(orderId)).isEqualTo("REFUNDED");
            assertThat(activeAccesses(orderId)).isZero();
            assertThat(audits("ACCESS_CANCELLED", orderId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("SEC-031: pagamento e estorno simultâneos terminam sempre em REFUNDED, sem Acesso ativo")
    void shouldEndRefundedWithoutAnActiveAccessWhenThePaymentAndTheRefundRace() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID customer = OrderTestSupport.insertCustomer(jdbc);
            UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), PRICE);
            String chargeId = newChargeId();
            UUID orderId = insertOrder(customer, product, chargeId);
            UUID paymentEvent = storeEvent(chargeId, "PAYMENT_RECEIVED");
            UUID refundEvent = storeEvent(chargeId, "PAYMENT_REFUNDED");

            // each task reads the charge at the same moment, and each sees a different state of it
            List<Callable<PaymentResult>> actions = List.of(
                    () -> {
                        gateway.answerChargeOnThisThread(chargeId, charge(chargeId, orderId, "RECEIVED"));
                        try {
                            return confirmation.process(paymentEvent);
                        } finally {
                            gateway.forgetThreadAnswers();
                        }
                    },
                    () -> {
                        gateway.answerChargeOnThisThread(chargeId, charge(chargeId, orderId, "REFUNDED"));
                        try {
                            return refund.process(refundEvent);
                        } finally {
                            gateway.forgetThreadAnswers();
                        }
                    });

            List<PaymentResult> results = together(actions);

            assertThat(results.get(1)).as("round %d: refund", round).isEqualTo(PaymentResult.REFUNDED);
            assertThat(results.get(0))
                    .as("round %d: payment", round)
                    .isIn(PaymentResult.PAID, PaymentResult.ALREADY_REFUNDED);
            assertThat(status(orderId)).as("round %d", round).isEqualTo("REFUNDED");
            assertThat(activeAccesses(orderId)).isZero();
            long granted = audits("ACCESS_GRANTED", orderId);
            assertThat(granted).isEqualTo(results.get(0) == PaymentResult.PAID ? 1 : 0);
            assertThat(audits("ACCESS_CANCELLED", orderId)).isEqualTo(granted);
        }
    }
}
