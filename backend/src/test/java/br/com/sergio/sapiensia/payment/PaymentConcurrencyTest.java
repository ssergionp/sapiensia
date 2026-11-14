package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.TOKEN;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.paymentEvent;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** ADR-0003, SEC-025, SEC-031: under races there is one paid Order, one Access and one audit entry. */
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=payment-concurrency",
            "app.login-limit.ip-max-attempts=1000",
            "db-scheduler.polling-interval=PT0.2S"
        })
class PaymentConcurrencyTest {

    private static final int ROUNDS = 20;
    private static final int PRICE = 2990;
    private static final Map<String, String> AUTH = Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    PaymentConfirmation confirmation;

    @Autowired
    OrderExpiration.Expirer expirer;

    @Autowired
    Orders orders;

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

    /** Polls the condition every 100 ms, for at most 20 s; never a fixed wait. */
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

    /** An Order straight in the database (pending, with its charge); the provider reports the charge as paid. */
    private UUID insertOrder(UUID customer, UUID product, String chargeId, Instant expiresAt) {
        UUID orderId = UUID.randomUUID();
        orders.insert(orderId, customer, product, PRICE, chargeId, Instant.now().minusSeconds(3600), expiresAt);
        gateway.answerCharge(
                chargeId,
                new PixCharge(chargeId, "RECEIVED", new BigDecimal("29.90"), "PIX", orderId.toString(), false));
        return orderId;
    }

    private static String newChargeId() {
        return "pay_" + UUID.randomUUID().toString().replace("-", "");
    }

    private UUID storeEvent(String chargeId, String type) {
        String json = "{\"event\":\"" + type + "\",\"payment\":{\"id\":\"" + chargeId + "\"}}";
        String evt = "evt_" + UUID.randomUUID().toString().replace("-", "");
        return events.insertIfAbsent(evt, type, json, OffsetDateTime.now()).orElseThrow();
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private long accessesOf(UUID customer, UUID product) {
        return jdbc.queryForObject(
                "select count(*) from accesses where customer_id = ? and product_id = ?",
                Long.class,
                customer,
                product);
    }

    private long grantedAudits(UUID... orderIds) {
        long total = 0;
        for (UUID id : orderIds) {
            total += jdbc.queryForObject(
                    "select count(*) from audit_events where action = 'ACCESS_GRANTED' and object_id = ?",
                    Long.class,
                    id);
        }
        return total;
    }

    private long finalResults(UUID eventId) {
        return jdbc.queryForObject(
                "select count(*) from payment_event_results where payment_event_id = ? and result <> 'GAVE_UP'",
                Long.class,
                eventId);
    }

    @Test
    @DisplayName("SEC-025, SEC-031: shouldGrantOnceWhenTheSameEventArrivesEightTimesAtOnce")
    void shouldGrantOnceWhenTheSameEventArrivesEightTimesAtOnce() throws Exception {
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), PRICE);
        for (int round = 0; round < ROUNDS; round++) {
            OrderHttp.Customer customer = OrderHttp.newCustomer(port, jdbc, encoder);
            UUID orderId = UUID.fromString(OrderHttp.json(customer.order(product, "52998224725"), "$.id"));
            String chargeId =
                    jdbc.queryForObject("select provider_payment_id from orders where id = ?", String.class, orderId);
            gateway.reset();
            String eventId = uniqueId();
            String body = paymentEvent(eventId, "PAYMENT_RECEIVED", chargeId);
            List<Callable<Integer>> posts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                posts.add(() -> {
                    HttpResponse<String> answer =
                            AsaasWebhookTestSupport.post(port, "127.0.0.1", AUTH, body, "application/json");
                    return answer.statusCode();
                });
            }

            assertThat(together(posts)).containsOnly(200);

            await("order " + orderId + " to be PAID", () -> "PAID".equals(status(orderId)));
            await(
                    "the task to finish",
                    () -> jdbc.queryForObject(
                                    "select count(*) from scheduled_tasks where task_name = 'process-payment-event'"
                                            + " and task_instance = (select id::text from payment_events"
                                            + " where provider_event_id = ?)",
                                    Integer.class,
                                    eventId)
                            == 0);
            assertThat(jdbc.queryForObject(
                            "select count(*) from payment_events where provider_event_id = ?", Integer.class, eventId))
                    .as("round %d: stored events", round)
                    .isEqualTo(1);
            assertThat(accessesOf(customer.id, product))
                    .as("round %d: accesses", round)
                    .isEqualTo(1);
            assertThat(grantedAudits(orderId)).as("round %d: audit", round).isEqualTo(1);
            assertThat(gateway.count("READ_CHARGE"))
                    .as("round %d: provider reads", round)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("SEC-025, SEC-031: shouldGrantOnceWhenTwoEventsOfTheSamePaymentAreProcessedTogether")
    void shouldGrantOnceWhenTwoEventsOfTheSamePaymentAreProcessedTogether() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID customer = OrderTestSupport.insertCustomer(jdbc);
            UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), PRICE);
            String chargeId = newChargeId();
            UUID orderId =
                    insertOrder(customer, product, chargeId, Instant.now().plus(Duration.ofMinutes(30)));
            UUID first = storeEvent(chargeId, "PAYMENT_RECEIVED");
            UUID second = storeEvent(chargeId, "PAYMENT_CONFIRMED");

            List<PaymentResult> results =
                    together(List.of(() -> confirmation.process(first), () -> confirmation.process(second)));

            assertThat(results)
                    .as("round %d", round)
                    .containsExactlyInAnyOrder(PaymentResult.PAID, PaymentResult.ALREADY_PAID);
            assertThat(status(orderId)).isEqualTo("PAID");
            assertThat(accessesOf(customer, product)).isEqualTo(1);
            assertThat(grantedAudits(orderId)).isEqualTo(1);
            assertThat(finalResults(first)).isEqualTo(1);
            assertThat(finalResults(second)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("SEC-025: shouldChangeNothingWhenTheTaskRunsTwice")
    void shouldChangeNothingWhenTheTaskRunsTwice() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID customer = OrderTestSupport.insertCustomer(jdbc);
            UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), PRICE);
            String chargeId = newChargeId();
            UUID orderId =
                    insertOrder(customer, product, chargeId, Instant.now().plus(Duration.ofMinutes(30)));
            UUID event = storeEvent(chargeId, "PAYMENT_RECEIVED");

            PaymentResult first = confirmation.process(event);
            Object paidAt = jdbc.queryForObject("select paid_at from orders where id = ?", Object.class, orderId);
            PaymentResult second = confirmation.process(event);

            assertThat(first).isEqualTo(PaymentResult.PAID);
            assertThat(second).as("round %d", round).isEqualTo(PaymentResult.PAID);
            assertThat(jdbc.queryForObject("select paid_at from orders where id = ?", Object.class, orderId))
                    .isEqualTo(paidAt);
            assertThat(accessesOf(customer, product)).isEqualTo(1);
            assertThat(grantedAudits(orderId)).isEqualTo(1);
            assertThat(finalResults(event)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "select count(*) from payment_event_results where payment_event_id = ?", Long.class, event))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("ADR-0003, SEC-031: shouldEndPaidWhenThePaymentAndTheExpirationRace")
    void shouldEndPaidWhenThePaymentAndTheExpirationRace() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID customer = OrderTestSupport.insertCustomer(jdbc);
            UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), PRICE);
            String chargeId = newChargeId();
            // already past its time: the expiration task wants it as much as the payment does
            UUID orderId =
                    insertOrder(customer, product, chargeId, Instant.now().minusSeconds(60));
            UUID event = storeEvent(chargeId, "PAYMENT_RECEIVED");

            together(List.<Callable<Object>>of(() -> expirer.run(), () -> confirmation.process(event)));

            assertThat(status(orderId)).as("round %d", round).isEqualTo("PAID");
            assertThat(accessesOf(customer, product)).isEqualTo(1);
            assertThat(grantedAudits(orderId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("SEC-025: shouldGrantOneAccessWhenTwoOrdersOfTheSameProductArePaidTogether")
    void shouldGrantOneAccessWhenTwoOrdersOfTheSameProductArePaidTogether() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID customer = OrderTestSupport.insertCustomer(jdbc);
            UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), PRICE);
            String chargeA = newChargeId();
            String chargeB = newChargeId();
            UUID orderA = insertOrder(customer, product, chargeA, Instant.now().plus(Duration.ofMinutes(30)));
            // only one pending Order per Customer and Product: the first one expires, then a second is made
            assertThat(orders.markExpired(orderA, Instant.now())).isTrue();
            UUID orderB = insertOrder(customer, product, chargeB, Instant.now().plus(Duration.ofMinutes(30)));
            UUID eventA = storeEvent(chargeA, "PAYMENT_RECEIVED");
            UUID eventB = storeEvent(chargeB, "PAYMENT_RECEIVED");

            List<PaymentResult> results =
                    together(List.of(() -> confirmation.process(eventA), () -> confirmation.process(eventB)));

            assertThat(results)
                    .as("round %d", round)
                    .containsExactlyInAnyOrder(PaymentResult.PAID, PaymentResult.DUPLICATE_PURCHASE);
            assertThat(status(orderA)).isEqualTo("PAID");
            assertThat(status(orderB)).isEqualTo("PAID");
            assertThat(accessesOf(customer, product)).isEqualTo(1);
            assertThat(grantedAudits(orderA, orderB)).isEqualTo(1);
        }
    }
}
