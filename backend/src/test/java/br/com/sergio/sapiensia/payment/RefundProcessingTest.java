package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.TOKEN;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.balanceEvent;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.paymentEvent;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.email.EmailMessage;
import br.com.sergio.sapiensia.product.LocalDownloadFixtures;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * FR-023, FR-025, SEC-026, SEC-031, ADR-0003, ADR-0007: a refund (or a MED) cancels the Access only when the provider
 * shows the charge as REFUNDED; from the webhook to the cancelled Access, through the retried task.
 */
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
// Same annotations and properties as PaymentEventProcessingTest, so the cached context is reused.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=payment-event-processing",
            "app.login-limit.ip-max-attempts=1000",
            "db-scheduler.polling-interval=PT0.2S",
            "app.orders.payment-processing.retry-delay=PT0.5S",
            "app.orders.payment-processing.max-attempts=5"
        })
class RefundProcessingTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final Map<String, String> AUTH = Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN);
    private static final String RUNBOOK = "../docs/runbooks/webhook-asaas.md";
    private static final String PLACEHOLDER = "evt_COLE_AQUI_O_ID_DO_EVENTO";
    private static final byte[] PDF = "%PDF-1.7 refund processing content".getBytes(StandardCharsets.UTF_8);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SwitchablePixGateway gateway;

    @Autowired
    ApplicationContext context;

    @Autowired
    Orders orders;

    @Autowired
    Accesses accesses;

    @Autowired
    RecordingEmailSender sender;

    @BeforeEach
    void setUp() {
        gateway.reset();
    }

    private record Placed(OrderHttp.Customer customer, UUID productId, UUID orderId, String chargeId) {}

    /** A pending Order created through the real API (so the fake provider knows its charge), with its own Product. */
    private Placed placeOrder() throws Exception {
        OrderHttp.Customer customer = OrderHttp.newCustomer(port, jdbc, encoder);
        UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), 2990);
        LocalDownloadFixtures.storeProductFile(context, jdbc, productId, PDF);
        HttpResponse<String> created = customer.order(productId, "52998224725");
        assertThat(created.statusCode()).isEqualTo(201);
        UUID orderId = UUID.fromString(OrderHttp.json(created, "$.id"));
        String chargeId =
                jdbc.queryForObject("select provider_payment_id from orders where id = ?", String.class, orderId);
        gateway.reset();
        return new Placed(customer, productId, orderId, chargeId);
    }

    /** A paid Order with its Access, as the payment confirmation leaves them. */
    private Placed placePaidOrder() throws Exception {
        Placed placed = placeOrder();
        Instant now = Instant.now();
        assertThat(orders.markPaid(placed.orderId(), now)).isTrue();
        assertThat(accesses.grant(placed.customer().id, placed.productId(), placed.orderId(), now))
                .isTrue();
        return placed;
    }

    private static PixCharge charge(Placed placed, String state) {
        return new PixCharge(
                placed.chargeId(),
                state,
                new BigDecimal("29.90"),
                "PIX",
                placed.orderId().toString(),
                false);
    }

    private void provider(Placed placed, String state) {
        gateway.answerCharge(placed.chargeId(), charge(placed, state));
    }

    private HttpResponse<String> post(String body) throws Exception {
        return AsaasWebhookTestSupport.post(port, LOOPBACK, AUTH, body, "application/json");
    }

    private void postEvent(String eventId, String type, Placed placed) throws Exception {
        assertThat(post(paymentEvent(eventId, type, placed.chargeId())).statusCode())
                .isEqualTo(200);
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private boolean accessCancelled(UUID orderId) {
        return jdbc.queryForObject(
                "select cancelled_at is not null from accesses where order_id = ?", Boolean.class, orderId);
    }

    private int accessRows(UUID orderId) {
        return jdbc.queryForObject("select count(*) from accesses where order_id = ?", Integer.class, orderId);
    }

    private int cancelAudits(UUID orderId) {
        return jdbc.queryForObject(
                "select count(*) from audit_events where action = 'ACCESS_CANCELLED' and object_type = 'ORDER'"
                        + " and object_id = ?",
                Integer.class,
                orderId);
    }

    private int results(String eventId, String result) {
        return jdbc.queryForObject(
                "select count(*) from payment_event_results r join payment_events e on e.id = r.payment_event_id"
                        + " where e.provider_event_id = ? and r.result = ?",
                Integer.class,
                eventId,
                result);
    }

    private int allResults(String eventId) {
        return jdbc.queryForObject(
                "select count(*) from payment_event_results r join payment_events e on e.id = r.payment_event_id"
                        + " where e.provider_event_id = ?",
                Integer.class,
                eventId);
    }

    private int events(String eventId) {
        return jdbc.queryForObject(
                "select count(*) from payment_events where provider_event_id = ?", Integer.class, eventId);
    }

    private int tasks(String eventId) {
        return jdbc.queryForObject(
                "select count(*) from scheduled_tasks where task_name = 'process-payment-event'"
                        + " and task_instance = (select id::text from payment_events where provider_event_id = ?)",
                Integer.class,
                eventId);
    }

    private void awaitResult(String eventId, String result) throws InterruptedException {
        await("result " + result + " of " + eventId, () -> results(eventId, result) == 1);
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

    private static String runbookSql() throws Exception {
        String text = Files.readString(Path.of(RUNBOOK));
        int start = text.indexOf("<!-- reprocess-sql:start -->");
        int end = text.indexOf("<!-- reprocess-sql:end -->");
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String block = text.substring(start, end);
        int open = block.indexOf("```sql");
        int close = block.lastIndexOf("```");
        return block.substring(open + "```sql".length(), close).trim();
    }

    private void assertAccessKept(Placed placed) throws Exception {
        assertThat(status(placed.orderId())).isEqualTo("PAID");
        assertThat(accessCancelled(placed.orderId())).isFalse();
        assertThat(cancelAudits(placed.orderId())).isZero();
        assertThat(placed.customer()
                        .post("/api/library/" + placed.productId() + "/download", "")
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName(
            "FR-023, FR-025: PAYMENT_REFUNDED com a cobrança REFUNDED marca o Pedido como REFUNDED e cancela o Acesso")
    void shouldRefundTheOrderAndCancelTheAccessWhenTheChargeIsRefunded() throws Exception {
        Placed placed = placePaidOrder();
        OrderHttp.Customer customer = placed.customer();
        String download = "/api/library/" + placed.productId() + "/download";
        assertThat(customer.post(download, "").statusCode()).isEqualTo(200);
        provider(placed, "REFUNDED");
        String eventId = uniqueId();

        postEvent(eventId, "PAYMENT_REFUNDED", placed);

        awaitResult(eventId, "REFUNDED");
        assertThat(status(placed.orderId())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                        "select refunded_at is not null from orders where id = ?", Boolean.class, placed.orderId()))
                .isTrue();
        assertThat(accessRows(placed.orderId())).isEqualTo(1);
        assertThat(accessCancelled(placed.orderId())).isTrue();
        assertThat(cancelAudits(placed.orderId())).isEqualTo(1);
        assertThat(OrderHttp.json(customer.get("/api/library"), "$.items.length()"))
                .isEqualTo("0");
        assertThat(customer.post(download, "").statusCode()).isEqualTo(404);
        assertThat(OrderHttp.json(customer.get("/api/orders"), "$.items[0].status"))
                .isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName(
            "SEC-026, SEC-031: aviso de estorno com a cobrança ainda paga repete, não cancela e termina em GAVE_UP; reprocessado com REFUNDED, cancela")
    void shouldRetryARefundNoticeWhileTheChargeStillReadsAsPaid() throws Exception {
        for (String paid : new String[] {"RECEIVED", "CONFIRMED"}) {
            Placed placed = placePaidOrder();
            provider(placed, paid);
            String eventId = uniqueId();

            postEvent(eventId, "PAYMENT_REFUNDED", placed);

            await("two reads while the charge reads as " + paid, () -> gateway.count("READ_CHARGE") >= 2);
            assertThat(allResults(eventId)).isZero();
            assertAccessKept(placed);
            await("the GAVE_UP result", () -> results(eventId, "GAVE_UP") == 1);
            await("the task to be removed", () -> tasks(eventId) == 0);
            assertThat(results(eventId, "NOT_REFUNDED")).isZero();
            assertAccessKept(placed);

            provider(placed, "REFUNDED");
            assertThat(jdbc.update(runbookSql().replace(PLACEHOLDER, eventId))).isEqualTo(1);

            awaitResult(eventId, "REFUNDED");
            assertThat(status(placed.orderId())).isEqualTo("REFUNDED");
            assertThat(accessCancelled(placed.orderId())).isTrue();
            gateway.reset();
        }
    }

    @Test
    @DisplayName("SEC-026: id ou referência externa divergente não cancela")
    void shouldCancelNothingWhenTheChargeIdOrTheReferenceDiffer() throws Exception {
        Placed placed = placePaidOrder();
        gateway.answerCharge(
                placed.chargeId(),
                new PixCharge(
                        "pay_other_charge",
                        "REFUNDED",
                        new BigDecimal("29.90"),
                        "PIX",
                        placed.orderId().toString(),
                        false));
        String first = uniqueId();
        postEvent(first, "PAYMENT_REFUNDED", placed);
        awaitResult(first, "CHARGE_ID_MISMATCH");

        gateway.answerCharge(
                placed.chargeId(),
                new PixCharge(
                        placed.chargeId(),
                        "REFUNDED",
                        new BigDecimal("29.90"),
                        "PIX",
                        UUID.randomUUID().toString(),
                        false));
        String second = uniqueId();
        postEvent(second, "PAYMENT_REFUNDED", placed);
        awaitResult(second, "REFERENCE_MISMATCH");

        assertAccessKept(placed);
    }

    @Test
    @DisplayName("FR-025: estorno parcial mantém o Acesso; com a cobrança REFUNDED, cancela")
    void shouldKeepTheAccessOnAPartialRefundUntilTheChargeIsRefunded() throws Exception {
        Placed placed = placePaidOrder();
        provider(placed, "RECEIVED");
        String partial = uniqueId();
        postEvent(partial, "PAYMENT_PARTIALLY_REFUNDED", placed);
        awaitResult(partial, "PARTIAL_REFUND");
        assertAccessKept(placed);

        provider(placed, "REFUNDED");
        String full = uniqueId();
        postEvent(full, "PAYMENT_PARTIALLY_REFUNDED", placed);

        awaitResult(full, "REFUNDED");
        assertThat(status(placed.orderId())).isEqualTo("REFUNDED");
        assertThat(accessCancelled(placed.orderId())).isTrue();
    }

    @Test
    @DisplayName("FR-025, ADR-0003: MED aberto só fica registrado; com a cobrança REFUNDED, cancela")
    void shouldOnlyRecordAnOpenMedAndCancelWhenTheChargeIsRefunded() throws Exception {
        Placed placed = placePaidOrder();
        provider(placed, "RECEIVED");
        String open = uniqueId();
        assertThat(post(balanceEvent(open, "BALANCE_VALUE_BLOCKED", "PIX_INFRACTION", placed.chargeId()))
                        .statusCode())
                .isEqualTo(200);
        awaitResult(open, "MED_BLOCKED");
        assertAccessKept(placed);

        String withoutCharge = uniqueId();
        assertThat(post(balanceEvent(withoutCharge, "BALANCE_VALUE_BLOCKED", "PIX_INFRACTION", null))
                        .statusCode())
                .isEqualTo(200);
        awaitResult(withoutCharge, "MED_WITHOUT_CHARGE");

        String judicial = uniqueId();
        assertThat(post(balanceEvent(judicial, "BALANCE_VALUE_BLOCKED", "JUDICIAL", placed.chargeId()))
                        .statusCode())
                .isEqualTo(200);
        awaitResult(judicial, "BALANCE_BLOCKED");
        assertAccessKept(placed);

        provider(placed, "REFUNDED");
        String refunded = uniqueId();
        assertThat(post(balanceEvent(refunded, "BALANCE_VALUE_BLOCKED", "PIX_INFRACTION", placed.chargeId()))
                        .statusCode())
                .isEqualTo(200);

        awaitResult(refunded, "REFUNDED");
        assertThat(status(placed.orderId())).isEqualTo("REFUNDED");
        assertThat(accessCancelled(placed.orderId())).isTrue();
    }

    @Test
    @DisplayName(
            "SEC-031: estorno antes do pagamento leva o Pedido a REFUNDED; o pagamento que chega depois não concede Acesso")
    void shouldKeepTheOrderRefundedWhenThePaymentArrivesAfterTheRefund() throws Exception {
        Placed placed = placeOrder();
        provider(placed, "REFUNDED");
        String refund = uniqueId();
        postEvent(refund, "PAYMENT_REFUNDED", placed);
        awaitResult(refund, "REFUNDED");
        assertThat(status(placed.orderId())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                        "select paid_at is null from orders where id = ?", Boolean.class, placed.orderId()))
                .isTrue();

        String payment = uniqueId();
        postEvent(payment, "PAYMENT_RECEIVED", placed);

        awaitResult(payment, "ALREADY_REFUNDED");
        assertThat(status(placed.orderId())).isEqualTo("REFUNDED");
        assertThat(accessRows(placed.orderId())).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from scheduled_tasks where task_name = 'order-paid-email'"
                                + " and task_instance = ?",
                        Integer.class,
                        placed.orderId().toString()))
                .isZero();
    }

    @Test
    @DisplayName("FR-023: o reembolso confirmado envia um e-mail ao Cliente, uma vez; estorno parcial não envia")
    void shouldSendOneRefundEmailOnlyWhenTheOrderIsRefunded() throws Exception {
        Placed placed = placePaidOrder();
        String email = placed.customer().email;
        provider(placed, "RECEIVED");
        String partial = uniqueId();
        postEvent(partial, "PAYMENT_PARTIALLY_REFUNDED", placed);
        awaitResult(partial, "PARTIAL_REFUND");
        assertThat(sender.awaitMessageTo(email, Duration.ofSeconds(1))).isEmpty();

        provider(placed, "REFUNDED");
        String first = uniqueId();
        postEvent(first, "PAYMENT_REFUNDED", placed);
        awaitResult(first, "REFUNDED");
        assertThat(sender.awaitMessageTo(email, Duration.ofSeconds(15)))
                .isPresent()
                .get()
                .extracting(EmailMessage::subject)
                .isEqualTo(OrderRefundedEmail.SUBJECT);

        String second = uniqueId();
        postEvent(second, "PAYMENT_REFUNDED", placed);
        awaitResult(second, "ALREADY_REFUNDED");
        assertThat(sender.awaitMessageTo(email, Duration.ofSeconds(1))).isEmpty();
        assertThat(sender.messagesTo(email)).hasSize(1);
    }

    @Test
    @DisplayName("SEC-031: segundo aviso de estorno não muda nada")
    void shouldChangeNothingOnASecondRefundNotice() throws Exception {
        Placed placed = placePaidOrder();
        provider(placed, "REFUNDED");
        String first = uniqueId();
        postEvent(first, "PAYMENT_REFUNDED", placed);
        awaitResult(first, "REFUNDED");
        long reads = gateway.count("READ_CHARGE");

        String second = uniqueId();
        postEvent(second, "PAYMENT_REFUNDED", placed);

        awaitResult(second, "ALREADY_REFUNDED");
        assertThat(cancelAudits(placed.orderId())).isEqualTo(1);
        assertThat(status(placed.orderId())).isEqualTo("REFUNDED");
        assertThat(gateway.count("READ_CHARGE")).isEqualTo(reads);
    }

    @Test
    @DisplayName(
            "SEC-031: estorno em andamento repete; Asaas fora do ar não cancela e termina em GAVE_UP; reagendado, cancela")
    void shouldRetryWhileTheRefundIsInProgressGiveUpWhenAsaasIsDownAndCancelWhenRescheduled() throws Exception {
        Placed placed = placePaidOrder();
        provider(placed, "REFUND_IN_PROGRESS");
        String eventId = uniqueId();
        postEvent(eventId, "PAYMENT_REFUNDED", placed);

        await("two reads while the refund is in progress", () -> gateway.count("READ_CHARGE") >= 2);
        assertThat(allResults(eventId)).isZero();
        assertThat(status(placed.orderId())).isEqualTo("PAID");
        gateway.failAt("READ_CHARGE", PixGatewayException.Kind.UNAVAILABLE);

        await("the GAVE_UP result", () -> results(eventId, "GAVE_UP") == 1);
        await("the task to be removed", () -> tasks(eventId) == 0);
        assertThat(status(placed.orderId())).isEqualTo("PAID");
        assertThat(accessCancelled(placed.orderId())).isFalse();

        gateway.failAt("READ_CHARGE", null);
        provider(placed, "REFUNDED");
        assertThat(jdbc.update(runbookSql().replace(PLACEHOLDER, eventId))).isEqualTo(1);

        awaitResult(eventId, "REFUNDED");
        assertThat(status(placed.orderId())).isEqualTo("REFUNDED");
        assertThat(accessCancelled(placed.orderId())).isTrue();
        assertThat(cancelAudits(placed.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-031: o mesmo evento reenviado é descartado e deixa um só resultado")
    void shouldDropARepeatedRefundEventAndKeepOneResult() throws Exception {
        Placed placed = placePaidOrder();
        provider(placed, "REFUNDED");
        String eventId = uniqueId();

        for (int i = 0; i < 3; i++) {
            postEvent(eventId, "PAYMENT_REFUNDED", placed);
        }

        awaitResult(eventId, "REFUNDED");
        await("the task to finish", () -> tasks(eventId) == 0);
        assertThat(events(eventId)).isEqualTo(1);
        assertThat(allResults(eventId)).isEqualTo(1);
        assertThat(gateway.count("READ_CHARGE")).isEqualTo(1);
        assertThat(cancelAudits(placed.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-025: estornar a compra duplicada não cancela o Acesso do outro Pedido")
    void shouldNotCancelTheAccessOfAnotherOrderWhenTheDuplicatePurchaseIsRefunded() throws Exception {
        Placed first = placePaidOrder();
        UUID duplicate = UUID.randomUUID();
        String duplicateCharge = "pay_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        orders.insert(
                duplicate,
                first.customer().id,
                first.productId(),
                2990,
                duplicateCharge,
                now.minusSeconds(60),
                now.plusSeconds(1800));
        assertThat(orders.markPaid(duplicate, now)).isTrue();
        gateway.answerCharge(
                duplicateCharge,
                new PixCharge(
                        duplicateCharge, "REFUNDED", new BigDecimal("29.90"), "PIX", duplicate.toString(), false));
        String eventId = uniqueId();

        assertThat(post(paymentEvent(eventId, "PAYMENT_REFUNDED", duplicateCharge))
                        .statusCode())
                .isEqualTo(200);

        awaitResult(eventId, "REFUNDED");
        assertThat(status(duplicate)).isEqualTo("REFUNDED");
        assertThat(cancelAudits(duplicate)).isZero();
        assertAccessKept(first);
        assertThat(OrderHttp.json(first.customer().get("/api/library"), "$.items.length()"))
                .isEqualTo("1");
        assertThat(sender.awaitMessageTo(first.customer().email, Duration.ofSeconds(2)))
                .isEmpty();
    }

    @Test
    @DisplayName("ADR-0007: despublicar o Produto não cancela o Acesso")
    void shouldKeepTheAccessWhenTheProductIsUnpublished() throws Exception {
        Placed placed = placePaidOrder();

        jdbc.update("update products set status = 'UNPUBLISHED' where id = ?", placed.productId());

        assertThat(accessCancelled(placed.orderId())).isFalse();
        assertThat(OrderHttp.json(placed.customer().get("/api/library"), "$.items.length()"))
                .isEqualTo("1");
        assertThat(placed.customer()
                        .post("/api/library/" + placed.productId() + "/download", "")
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-026, SEC-031: estorno de uma cobrança sem Pedido só fica registrado como NO_ORDER")
    void shouldOnlyRecordARefundForAChargeWithoutAnOrder() throws Exception {
        Placed placed = placePaidOrder();
        String eventId = uniqueId();

        assertThat(post(paymentEvent(
                                eventId,
                                "PAYMENT_REFUNDED",
                                "pay_no_order_" + UUID.randomUUID().toString().replace("-", "")))
                        .statusCode())
                .isEqualTo(200);

        awaitResult(eventId, "NO_ORDER");
        assertThat(gateway.count("READ_CHARGE")).isZero();
        assertAccessKept(placed);
    }

    @Test
    @DisplayName("SEC-026, SEC-031: estorno sem o id da cobrança fica como INVALID_PAYLOAD e não cancela nada")
    void shouldRecordInvalidPayloadForARefundWithoutAChargeId() throws Exception {
        Placed placed = placePaidOrder();
        String eventId = uniqueId();
        String body = "{\"id\": \"" + eventId + "\", \"event\": \"PAYMENT_REFUNDED\", \"payment\": {}}";

        assertThat(post(body).statusCode()).isEqualTo(200);

        awaitResult(eventId, "INVALID_PAYLOAD");
        assertThat(gateway.count("READ_CHARGE")).isZero();
        assertAccessKept(placed);
    }

    @Test
    @DisplayName(
            "SEC-026, SEC-031: cobrança que o Asaas não conhece (404) fica como CHARGE_NOT_FOUND e não cancela nada")
    void shouldCancelNothingWhenAsaasDoesNotKnowTheCharge() throws Exception {
        Placed placed = placePaidOrder();
        gateway.failAt("READ_CHARGE", PixGatewayException.Kind.REFUSED);
        String eventId = uniqueId();

        postEvent(eventId, "PAYMENT_REFUNDED", placed);

        awaitResult(eventId, "CHARGE_NOT_FOUND");
        gateway.failAt("READ_CHARGE", null);
        assertAccessKept(placed);
    }

    @Test
    @DisplayName("SEC-026, SEC-031: estado da cobrança desconhecido ou vazio fica como NOT_REFUNDED e não cancela nada")
    void shouldCancelNothingWhenTheChargeStateIsUnknownOrMissing() throws Exception {
        Placed placed = placePaidOrder();
        String unknown = uniqueId();
        provider(placed, "SOMETHING_NEW");
        postEvent(unknown, "PAYMENT_REFUNDED", placed);
        awaitResult(unknown, "NOT_REFUNDED");
        assertAccessKept(placed);

        String missing = uniqueId();
        provider(placed, null);
        postEvent(missing, "PAYMENT_REFUNDED", placed);

        awaitResult(missing, "NOT_REFUNDED");
        assertAccessKept(placed);
    }

    @Test
    @DisplayName("SEC-031: evento de estorno com token inválido recebe 401 e não é gravado")
    void shouldRefuseARefundEventWithAnInvalidToken() throws Exception {
        Placed placed = placePaidOrder();
        provider(placed, "REFUNDED");
        String eventId = uniqueId();

        HttpResponse<String> response = AsaasWebhookTestSupport.post(
                port,
                LOOPBACK,
                Map.of(AsaasWebhookAccess.TOKEN_HEADER, "wrong-token"),
                paymentEvent(eventId, "PAYMENT_REFUNDED", placed.chargeId()),
                "application/json");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(events(eventId)).isZero();
        assertThat(status(placed.orderId())).isEqualTo("PAID");
        assertThat(gateway.count("READ_CHARGE")).isZero();
    }
}
