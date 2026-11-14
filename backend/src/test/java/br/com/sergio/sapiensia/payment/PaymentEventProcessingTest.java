package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.TOKEN;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.paymentEvent;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** FR-021, FR-024, SEC-025, SEC-027, SEC-031: from the webhook to the paid Order, through the retried task. */
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
class PaymentEventProcessingTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final Map<String, String> AUTH = Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN);
    private static final String RUNBOOK = "../docs/runbooks/webhook-asaas.md";
    private static final String PLACEHOLDER = "evt_COLE_AQUI_O_ID_DO_EVENTO";
    private static final String COUNT_TASKS =
            "select count(*) from scheduled_tasks where task_name = 'process-payment-event'";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SwitchablePixGateway gateway;

    @Autowired
    AsaasWebhookService webhook;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        gateway.reset();
    }

    /** An Order created through the real API, so the fake provider knows its charge. */
    private record Placed(UUID orderId, String chargeId) {}

    private Placed placeOrder() throws Exception {
        OrderHttp.Customer customer = OrderHttp.newCustomer(port, jdbc, encoder);
        UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), 2990);
        HttpResponse<String> created = customer.order(productId, "52998224725");
        assertThat(created.statusCode()).isEqualTo(201);
        UUID orderId = UUID.fromString(OrderHttp.json(created, "$.id"));
        String chargeId =
                jdbc.queryForObject("select provider_payment_id from orders where id = ?", String.class, orderId);
        gateway.reset();
        return new Placed(orderId, chargeId);
    }

    private HttpResponse<String> post(String eventId, String type, String chargeId) throws Exception {
        return AsaasWebhookTestSupport.post(
                port, LOOPBACK, AUTH, paymentEvent(eventId, type, chargeId), "application/json");
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private int accesses(UUID orderId) {
        return jdbc.queryForObject("select count(*) from accesses where order_id = ?", Integer.class, orderId);
    }

    private int events(String eventId) {
        return jdbc.queryForObject(
                "select count(*) from payment_events where provider_event_id = ?", Integer.class, eventId);
    }

    private int tasks(String eventId) {
        return jdbc.queryForObject(
                COUNT_TASKS + " and task_instance = (select id::text from payment_events where provider_event_id = ?)",
                Integer.class,
                eventId);
    }

    private int results(String eventId, String result) {
        return jdbc.queryForObject(
                "select count(*) from payment_event_results r join payment_events e on e.id = r.payment_event_id"
                        + " where e.provider_event_id = ? and r.result = ?",
                Integer.class,
                eventId,
                result);
    }

    private int finalResults(String eventId) {
        return jdbc.queryForObject(
                "select count(*) from payment_event_results r join payment_events e on e.id = r.payment_event_id"
                        + " where e.provider_event_id = ? and r.result <> 'GAVE_UP'",
                Integer.class,
                eventId);
    }

    /** Polls the condition every 150 ms, for at most 20 s; never a fixed wait. */
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

    private void awaitPaid(UUID orderId) throws InterruptedException {
        await("order " + orderId + " to be PAID", () -> "PAID".equals(status(orderId)));
    }

    /** The SQL of the runbook, between its markers; fails clearly when the markers are missing. */
    private static String runbookSql() throws Exception {
        String text = Files.readString(Path.of(RUNBOOK));
        int start = text.indexOf("<!-- reprocess-sql:start -->");
        int end = text.indexOf("<!-- reprocess-sql:end -->");
        assertThat(start).as("start marker in " + RUNBOOK).isGreaterThanOrEqualTo(0);
        assertThat(end).as("end marker in " + RUNBOOK).isGreaterThan(start);
        String block = text.substring(start, end);
        int open = block.indexOf("```sql");
        int close = block.lastIndexOf("```");
        assertThat(open).as("sql block between the markers").isGreaterThanOrEqualTo(0);
        assertThat(close).isGreaterThan(open);
        return block.substring(open + "```sql".length(), close).trim();
    }

    private int reschedule(String eventId) throws Exception {
        return jdbc.update(runbookSql().replace(PLACEHOLDER, eventId));
    }

    @Test
    @DisplayName("FR-021, FR-024: shouldPayTheOrderAfterAnAuthenticatedPaymentEvent")
    void shouldPayTheOrderAfterAnAuthenticatedPaymentEvent() throws Exception {
        Placed placed = placeOrder();

        assertThat(post(uniqueId(), "PAYMENT_RECEIVED", placed.chargeId()).statusCode())
                .isEqualTo(200);

        awaitPaid(placed.orderId());
        assertThat(accesses(placed.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-021: shouldAnswerOkWithoutWaitingForTheProvider")
    void shouldAnswerOkWithoutWaitingForTheProvider() throws Exception {
        Placed placed = placeOrder();
        CountDownLatch latch = new CountDownLatch(1);
        gateway.holdNextRead(latch);
        try {
            assertThat(post(uniqueId(), "PAYMENT_CONFIRMED", placed.chargeId()).statusCode())
                    .isEqualTo(200);

            await("the task to reach the provider", () -> gateway.count("READ_CHARGE") >= 1);
            assertThat(status(placed.orderId())).isEqualTo("PENDING");
        } finally {
            latch.countDown();
        }
        awaitPaid(placed.orderId());
    }

    @Test
    @DisplayName("FR-021: shouldStoreTheEventAndTheTaskInOneTransaction")
    void shouldStoreTheEventAndTheTaskInOneTransaction() {
        String eventId = uniqueId();
        int before = jdbc.queryForObject(COUNT_TASKS, Integer.class);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            webhook.receive(paymentEvent(eventId, "PAYMENT_RECEIVED", "pay_never_exists"));
            assertThat(jdbc.queryForObject(COUNT_TASKS, Integer.class))
                    .as("the task is in the same transaction as the event")
                    .isEqualTo(before + 1);
            status.setRollbackOnly();
        });

        assertThat(events(eventId)).isZero();
        assertThat(jdbc.queryForObject(COUNT_TASKS, Integer.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-025: shouldScheduleNothingForARepeatedEvent")
    void shouldScheduleNothingForARepeatedEvent() throws Exception {
        Placed placed = placeOrder();
        String eventId = uniqueId();

        for (int i = 0; i < 3; i++) {
            assertThat(post(eventId, "PAYMENT_RECEIVED", placed.chargeId()).statusCode())
                    .isEqualTo(200);
        }

        awaitPaid(placed.orderId());
        await("the task to finish", () -> tasks(eventId) == 0);
        assertThat(events(eventId)).isEqualTo(1);
        assertThat(gateway.count("READ_CHARGE")).isEqualTo(1);
        assertThat(accesses(placed.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-031: shouldRetryUntilTheProviderIsBack")
    void shouldRetryUntilTheProviderIsBack() throws Exception {
        Placed placed = placeOrder();
        gateway.failAt("READ_CHARGE", PixGatewayException.Kind.UNAVAILABLE);

        assertThat(post(uniqueId(), "PAYMENT_RECEIVED", placed.chargeId()).statusCode())
                .isEqualTo(200);

        await("two failed attempts", () -> gateway.count("READ_CHARGE") >= 2);
        assertThat(status(placed.orderId())).isEqualTo("PENDING");
        gateway.failAt("READ_CHARGE", null);

        awaitPaid(placed.orderId());
        assertThat(accesses(placed.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-031: shouldGiveUpAfterTheLastAttemptAndKeepTheEvent")
    void shouldGiveUpAfterTheLastAttemptAndKeepTheEvent() throws Exception {
        Placed placed = placeOrder();
        String eventId = uniqueId();
        gateway.failAt("READ_CHARGE", PixGatewayException.Kind.UNAVAILABLE);

        assertThat(post(eventId, "PAYMENT_RECEIVED", placed.chargeId()).statusCode())
                .isEqualTo(200);

        await("the GAVE_UP result", () -> results(eventId, "GAVE_UP") == 1);
        await("the task to be removed", () -> tasks(eventId) == 0);
        assertThat(events(eventId)).isEqualTo(1);
        assertThat(gateway.count("READ_CHARGE")).isEqualTo(5);
        assertThat(status(placed.orderId())).isEqualTo("PENDING");
        assertThat(accesses(placed.orderId())).isZero();
    }

    @Test
    @DisplayName("SEC-031: shouldPayAfterTheRunbookReschedulesAnEventThatWasGivenUp")
    void shouldPayAfterTheRunbookReschedulesAnEventThatWasGivenUp() throws Exception {
        Placed placed = placeOrder();
        String eventId = uniqueId();
        gateway.failAt("READ_CHARGE", PixGatewayException.Kind.UNAVAILABLE);
        assertThat(post(eventId, "PAYMENT_RECEIVED", placed.chargeId()).statusCode())
                .isEqualTo(200);
        await("the GAVE_UP result", () -> results(eventId, "GAVE_UP") == 1);
        await("the task to be removed", () -> tasks(eventId) == 0);
        gateway.failAt("READ_CHARGE", null);

        // two runs in one transaction: the second finds the task of the first and leaves it alone (on conflict)
        int[] inserted = new int[2];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                inserted[0] = reschedule(eventId);
                inserted[1] = reschedule(eventId);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(inserted).containsExactly(1, 0);

        awaitPaid(placed.orderId());
        await("the task to finish", () -> tasks(eventId) == 0);
        assertThat(accesses(placed.orderId())).isEqualTo(1);
        assertThat(finalResults(eventId)).isEqualTo(1);

        // running it again after the payment changes nothing: the final result is already there
        assertThat(reschedule(eventId)).isEqualTo(1);
        await("the second task to finish", () -> tasks(eventId) == 0);
        assertThat(accesses(placed.orderId())).isEqualTo(1);
        assertThat(finalResults(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-021: shouldDoNothingWhenTheRunbookSqlNamesAnEventThatIsNotAPayment")
    void shouldDoNothingWhenTheRunbookSqlNamesAnEventThatIsNotAPayment() throws Exception {
        String eventId = uniqueId();
        assertThat(post(eventId, "PAYMENT_CREATED", "pay_whatever").statusCode())
                .isEqualTo(200);

        assertThat(reschedule(eventId)).isZero();

        assertThat(tasks(eventId)).isZero();
    }

    @Test
    @DisplayName("SEC-031: shouldAnswerOkAndGrantNothingForAChargeWithoutAnOrder")
    void shouldAnswerOkAndGrantNothingForAChargeWithoutAnOrder() throws Exception {
        String eventId = uniqueId();
        int accessesBefore = jdbc.queryForObject("select count(*) from accesses", Integer.class);

        assertThat(post(
                                eventId,
                                "PAYMENT_RECEIVED",
                                "pay_no_order_" + UUID.randomUUID().toString().replace("-", ""))
                        .statusCode())
                .isEqualTo(200);

        await("the NO_ORDER result", () -> results(eventId, "NO_ORDER") == 1);
        assertThat(jdbc.queryForObject("select count(*) from accesses", Integer.class))
                .isEqualTo(accessesBefore);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "PAYMENT_CREATED",
                "PAYMENT_OVERDUE",
                "PAYMENT_DELETED",
                "PAYMENT_REFUNDED",
                "PAYMENT_CHARGEBACK_REQUESTED",
                "BALANCE_VALUE_BLOCKED",
                "SOMETHING_NEW"
            })
    @DisplayName("FR-021: shouldOnlyStoreEventsThatAreNotPayments")
    void shouldOnlyStoreEventsThatAreNotPayments(String type) throws Exception {
        Placed placed = placeOrder();
        String eventId = uniqueId();

        assertThat(post(eventId, type, placed.chargeId()).statusCode()).isEqualTo(200);

        assertThat(events(eventId)).isEqualTo(1);
        assertThat(tasks(eventId)).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from payment_event_results r join payment_events e"
                                + " on e.id = r.payment_event_id where e.provider_event_id = ?",
                        Integer.class,
                        eventId))
                .isZero();
        assertThat(status(placed.orderId())).isEqualTo("PENDING");
        assertThat(accesses(placed.orderId())).isZero();
    }

    @Test
    @DisplayName("SEC-027: shouldRefuseAPaymentEventFromALoggedInCustomerWithoutTheToken")
    void shouldRefuseAPaymentEventFromALoggedInCustomerWithoutTheToken() throws Exception {
        OrderHttp.Customer customer = OrderHttp.newCustomer(port, jdbc, encoder);
        UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), 2990);
        UUID orderId = UUID.fromString(OrderHttp.json(customer.order(productId, "52998224725"), "$.id"));
        String chargeId =
                jdbc.queryForObject("select provider_payment_id from orders where id = ?", String.class, orderId);
        String eventId = uniqueId();

        HttpResponse<String> response =
                customer.post("/api/webhooks/asaas", paymentEvent(eventId, "PAYMENT_RECEIVED", chargeId));

        // a session is not the credential of this route: refused (403 for an authenticated caller; 401 for none)
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(events(eventId)).isZero();
        assertThat(status(orderId)).isEqualTo("PENDING");
    }
}
