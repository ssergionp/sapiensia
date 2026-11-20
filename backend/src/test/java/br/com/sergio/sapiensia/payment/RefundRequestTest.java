package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.TOKEN;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.paymentEvent;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.customer.CustomerAnonymization;
import br.com.sergio.sapiensia.email.EmailMessage;
import br.com.sergio.sapiensia.product.LocalDownloadFixtures;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * FR-023, FR-025, FR-019, SEC-011, SEC-012: the logged-in Customer asks for the Refund of a paid Order within 7 days;
 * the request is stored, the Access of that Order is suspended, and the Order stays PAID until Asaas refunds it.
 */
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
class RefundRequestTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String CPF = "52998224725";
    private static final byte[] PDF = "%PDF-1.7 refund request content".getBytes(StandardCharsets.UTF_8);
    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    @Autowired
    CustomerAnonymization anonymization;

    @BeforeEach
    void setUp() {
        gateway.reset();
    }

    private record Placed(OrderHttp.Customer customer, UUID productId, UUID orderId, String chargeId) {}

    /** A pending Order of this Customer created through the real API, with its own Product. */
    private Placed placeOrder(OrderHttp.Customer customer) throws Exception {
        UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), 2990);
        LocalDownloadFixtures.storeProductFile(context, jdbc, productId, PDF);
        HttpResponse<String> created = customer.order(productId, CPF);
        assertThat(created.statusCode()).isEqualTo(201);
        UUID orderId = UUID.fromString(OrderHttp.json(created, "$.id"));
        String chargeId =
                jdbc.queryForObject("select provider_payment_id from orders where id = ?", String.class, orderId);
        gateway.reset();
        return new Placed(customer, productId, orderId, chargeId);
    }

    private Placed placeOrder() throws Exception {
        return placeOrder(OrderHttp.newCustomer(port, jdbc, encoder));
    }

    /** A paid Order with its Access, as the payment confirmation leaves them. */
    private Placed placePaidOrder(OrderHttp.Customer customer) throws Exception {
        Placed placed = placeOrder(customer);
        Instant now = Instant.now();
        assertThat(orders.markPaid(placed.orderId(), now)).isTrue();
        assertThat(accesses.grant(customer.id, placed.productId(), placed.orderId(), now))
                .isTrue();
        return placed;
    }

    private Placed placePaidOrder() throws Exception {
        return placePaidOrder(OrderHttp.newCustomer(port, jdbc, encoder));
    }

    private static String body(String reason, String details) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (reason != null) {
            body.put("reason", reason);
        }
        if (details != null) {
            body.put("details", details);
        }
        return JSON.writeValueAsString(body);
    }

    private static HttpResponse<String> request(OrderHttp.Customer customer, UUID orderId, String json)
            throws Exception {
        return customer.post("/api/orders/" + orderId + "/refund-request", json);
    }

    private static HttpResponse<String> request(Placed placed, String reason, String details) throws Exception {
        return request(placed.customer(), placed.orderId(), body(reason, details));
    }

    private void paidAt(UUID orderId, Instant paidAt) {
        jdbc.update("update orders set paid_at = ? where id = ?", Timestamp.from(paidAt), orderId);
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private int requestRows(UUID orderId) {
        return jdbc.queryForObject("select count(*) from refund_requests where order_id = ?", Integer.class, orderId);
    }

    private boolean suspended(UUID orderId) {
        return jdbc.queryForObject(
                "select suspended_at is not null from accesses where order_id = ?", Boolean.class, orderId);
    }

    private int audits(UUID orderId) {
        return jdbc.queryForObject(
                "select count(*) from audit_events where action = 'REFUND_REQUESTED' and object_type = 'ORDER'"
                        + " and object_id = ? and actor_id is null",
                Integer.class,
                orderId);
    }

    /** The occurrences of the alerts of this type for this Order (0 when there is none). */
    private int alertOccurrences(String type, UUID orderId) {
        return jdbc.queryForObject(
                "select coalesce(sum(occurrences), 0) from admin_alerts where type = ? and order_id = ?"
                        + " and detail is null and payment_event_id is null",
                Integer.class,
                type,
                orderId);
    }

    private int alertRows(String type, UUID orderId) {
        return jdbc.queryForObject(
                "select count(*) from admin_alerts where type = ? and order_id = ?", Integer.class, type, orderId);
    }

    private static String libraryItems(OrderHttp.Customer customer) throws Exception {
        return OrderHttp.json(customer.get("/api/library"), "$.items.length()");
    }

    private static int download(Placed placed) throws Exception {
        return placed.customer()
                .post("/api/library/" + placed.productId() + "/download", "")
                .statusCode();
    }

    private static void assertRefusal(HttpResponse<String> response, String field, String code) {
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(OrderHttp.json(response, "$.errors." + field)).isEqualTo(code);
    }

    @Test
    @DisplayName(
            "FR-023: Pedido pago dentro do prazo — 201, solicitação gravada, Acesso suspenso, Pedido continua PAID")
    void shouldStoreTheRequestAndSuspendTheAccessOfAPaidOrderWithinTheWindow() throws Exception {
        Placed placed = placePaidOrder();
        assertThat(download(placed)).isEqualTo(200);

        HttpResponse<String> response = request(placed, "DEFECTIVE_FILE", "O arquivo não abre");

        assertThat(response.statusCode()).isEqualTo(201);
        Instant requestedAt = Instant.parse(OrderHttp.json(response, "$.requestedAt"));
        Map<String, Object> row = jdbc.queryForMap(
                "select reason, details, customer_id, requested_at from refund_requests where order_id = ?",
                placed.orderId());
        assertThat(row.get("reason")).isEqualTo("DEFECTIVE_FILE");
        assertThat(row.get("details")).isEqualTo("O arquivo não abre");
        assertThat(row.get("customer_id")).isEqualTo(placed.customer().id);
        assertThat(((Timestamp) row.get("requested_at")).toInstant()).isEqualTo(requestedAt);
        assertThat(suspended(placed.orderId())).isTrue();
        assertThat(status(placed.orderId())).isEqualTo("PAID");
        assertThat(libraryItems(placed.customer())).isEqualTo("0");
        assertThat(download(placed)).isEqualTo(404);
        assertThat(audits(placed.orderId())).isEqualTo(1);
        assertThat(alertOccurrences("REFUND_REQUESTED", placed.orderId())).isEqualTo(1);
        assertThat(alertRows("REFUND_REQUESTS_REPEATED", placed.orderId())).isZero();
    }

    @Test
    @DisplayName("FR-023: repetir a solicitação — 200, mesma data, uma linha, um Alerta")
    void shouldAnswerTheStoredDateWhenTheRequestIsRepeated() throws Exception {
        Placed placed = placePaidOrder();
        HttpResponse<String> first = request(placed, "NOT_AS_EXPECTED", null);
        assertThat(first.statusCode()).isEqualTo(201);

        HttpResponse<String> second = request(placed, "OTHER", "Mudei de ideia");

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(OrderHttp.json(second, "$.requestedAt")).isEqualTo(OrderHttp.json(first, "$.requestedAt"));
        assertThat(requestRows(placed.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select reason from refund_requests where order_id = ?", String.class, placed.orderId()))
                .isEqualTo("NOT_AS_EXPECTED");
        assertThat(alertOccurrences("REFUND_REQUESTED", placed.orderId())).isEqualTo(1);
        assertThat(audits(placed.orderId())).isEqualTo(1);

        // the stored request is read back even once the window has closed
        paidAt(placed.orderId(), Instant.now().minus(Duration.ofDays(30)));
        HttpResponse<String> late = request(placed, "OTHER", null);
        assertThat(late.statusCode()).isEqualTo(200);
        assertThat(OrderHttp.json(late, "$.requestedAt")).isEqualTo(OrderHttp.json(first, "$.requestedAt"));
    }

    @Test
    @DisplayName("FR-023: duas solicitações simultâneas — uma linha só")
    void shouldStoreOneRowForTwoSimultaneousRequests() throws Exception {
        Placed placed = placePaidOrder();
        String json = body("BOUGHT_BY_MISTAKE", null);
        CountDownLatch start = new CountDownLatch(1);
        Callable<HttpResponse<String>> call = () -> {
            start.await();
            return request(placed.customer(), placed.orderId(), json);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> a = pool.submit(call);
            Future<HttpResponse<String>> b = pool.submit(call);
            start.countDown();
            HttpResponse<String> first = a.get();
            HttpResponse<String> second = b.get();

            assertThat(List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(201, 200);
            assertThat(OrderHttp.json(first, "$.requestedAt")).isEqualTo(OrderHttp.json(second, "$.requestedAt"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(requestRows(placed.orderId())).isEqualTo(1);
        assertThat(alertOccurrences("REFUND_REQUESTED", placed.orderId())).isEqualTo(1);
        assertThat(audits(placed.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-011: Pedido de outro Cliente — 404 e nada muda")
    void shouldAnswerNotFoundForAnOrderOfAnotherCustomer() throws Exception {
        Placed placed = placePaidOrder();
        OrderHttp.Customer other = OrderHttp.newCustomer(port, jdbc, encoder);

        HttpResponse<String> response = request(other, placed.orderId(), body("DEFECTIVE_FILE", "Não abre"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
        assertThat(request(other, UUID.randomUUID(), body("DEFECTIVE_FILE", null))
                        .statusCode())
                .isEqualTo(404);
        assertThat(requestRows(placed.orderId())).isZero();
        assertThat(suspended(placed.orderId())).isFalse();
        assertThat(audits(placed.orderId())).isZero();
        assertThat(alertRows("REFUND_REQUESTED", placed.orderId())).isZero();
        assertThat(download(placed)).isEqualTo(200);
    }

    @Test
    @DisplayName("FR-023: pagamento há 8 dias — 409 WINDOW_CLOSED e o Acesso continua ativo")
    void shouldRefuseTheRequestWhenTheWindowIsClosed() throws Exception {
        Placed placed = placePaidOrder();
        paidAt(placed.orderId(), Instant.now().minus(Duration.ofDays(8)));

        HttpResponse<String> response = request(placed, "NOT_AS_EXPECTED", null);

        assertRefusal(response, "refund", "WINDOW_CLOSED");
        assertThat(requestRows(placed.orderId())).isZero();
        assertThat(suspended(placed.orderId())).isFalse();
        assertThat(audits(placed.orderId())).isZero();
        assertThat(alertRows("REFUND_REQUESTED", placed.orderId())).isZero();
        assertThat(download(placed)).isEqualTo(200);
    }

    @Test
    @DisplayName("FR-023: pagamento no 7º dia — 201")
    void shouldAcceptTheRequestOnTheSeventhDay() throws Exception {
        Placed placed = placePaidOrder();
        paidAt(placed.orderId(), Instant.now().minus(Duration.ofDays(7)));

        assertThat(request(placed, "NOT_AS_EXPECTED", null).statusCode()).isEqualTo(201);
        assertThat(suspended(placed.orderId())).isTrue();
    }

    @Test
    @DisplayName("FR-023: Pedido pendente, expirado ou reembolsado — 409 NOT_AVAILABLE")
    void shouldRefuseTheRequestOfAnOrderThatIsNotPaid() throws Exception {
        Placed pending = placeOrder();
        assertRefusal(request(pending, "OTHER", null), "refund", "NOT_AVAILABLE");

        Placed expired = placeOrder();
        jdbc.update("update orders set status = 'EXPIRED', expired_at = now() where id = ?", expired.orderId());
        assertRefusal(request(expired, "OTHER", null), "refund", "NOT_AVAILABLE");

        Placed refunded = placePaidOrder();
        jdbc.update("update orders set status = 'REFUNDED', refunded_at = now() where id = ?", refunded.orderId());
        assertRefusal(request(refunded, "OTHER", null), "refund", "NOT_AVAILABLE");

        for (Placed placed : List.of(pending, expired, refunded)) {
            assertThat(requestRows(placed.orderId())).isZero();
            assertThat(audits(placed.orderId())).isZero();
            assertThat(alertRows("REFUND_REQUESTED", placed.orderId())).isZero();
        }
        assertThat(suspended(refunded.orderId())).isFalse();
    }

    @Test
    @DisplayName(
            "SEC-012: motivo fora da lista — 400; comentário com 501 caracteres — 400; comentário só com espaços — gravado como nulo")
    void shouldValidateTheReasonAndTheDetails() throws Exception {
        Placed placed = placePaidOrder();

        assertThat(request(placed, "MONEY_BACK", null).statusCode()).isEqualTo(400);
        assertThat(request(placed, "defective_file", null).statusCode()).isEqualTo(400);
        // the position of a code in the list is not a code
        assertThat(request(placed.customer(), placed.orderId(), "{\"reason\":1}")
                        .statusCode())
                .isEqualTo(400);
        HttpResponse<String> missing = request(placed, null, "Sem motivo");
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(OrderHttp.json(missing, "$.errors.reason")).isEqualTo("REASON_REQUIRED");
        HttpResponse<String> tooLong = request(placed, "OTHER", "a".repeat(501));
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(OrderHttp.json(tooLong, "$.errors.details")).isEqualTo("DETAILS_TOO_LONG");
        // the limit applies to the text as sent, before the spaces are stripped
        assertThat(request(placed, "OTHER", "a".repeat(500) + " ").statusCode()).isEqualTo(400);
        assertThat(requestRows(placed.orderId())).isZero();
        assertThat(suspended(placed.orderId())).isFalse();

        assertThat(request(placed, "OTHER", "   \n\t ").statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                        "select details is null from refund_requests where order_id = ?",
                        Boolean.class,
                        placed.orderId()))
                .isTrue();
    }

    @Test
    @DisplayName("SEC-012: o limite do comentário conta caracteres — 500 emojis passam, 501 não")
    void shouldCountCharactersNotUtf16UnitsInTheDetailsLimit() throws Exception {
        Placed placed = placePaidOrder();
        String emoji = Character.toString(0x1F600);

        HttpResponse<String> tooLong = request(placed, "OTHER", emoji.repeat(501));
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(OrderHttp.json(tooLong, "$.errors.details")).isEqualTo("DETAILS_TOO_LONG");
        assertThat(requestRows(placed.orderId())).isZero();

        assertThat(request(placed, "OTHER", emoji.repeat(500)).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                        "select char_length(details) from refund_requests where order_id = ?",
                        Integer.class,
                        placed.orderId()))
                .isEqualTo(500);
    }

    @Test
    @DisplayName(
            "SEC-012: comentário com HTML, quebra de linha e emoji — gravado como texto, sem os espaços das pontas")
    void shouldStoreTheDetailsAsPlainText() throws Exception {
        Placed placed = placePaidOrder();
        String text = "<b>Não abre</b>\n<script>alert(1)</script> 😀";

        assertThat(request(placed, "DEFECTIVE_FILE", "  " + text + "\n ").statusCode())
                .isEqualTo(201);

        assertThat(jdbc.queryForObject(
                        "select details from refund_requests where order_id = ?", String.class, placed.orderId()))
                .isEqualTo(text);
    }

    @Test
    @DisplayName("FR-023: compra duplicada — a solicitação do Pedido sem Acesso não suspende o Acesso do outro Pedido")
    void shouldNotSuspendTheAccessOfAnotherOrderWhenTheDuplicatePurchaseIsRequested() throws Exception {
        Placed first = placePaidOrder();
        UUID duplicate = paidDuplicateOf(first);

        HttpResponse<String> response = request(first.customer(), duplicate, body("BOUGHT_BY_MISTAKE", null));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(requestRows(duplicate)).isEqualTo(1);
        assertThat(suspended(first.orderId())).isFalse();
        assertThat(libraryItems(first.customer())).isEqualTo("1");
        assertThat(download(first)).isEqualTo(200);
    }

    /** A second paid Order of the same Product, inserted as the duplicate-purchase test does: no Access of its own. */
    private UUID paidDuplicateOf(Placed first) {
        UUID duplicate = UUID.randomUUID();
        Instant now = Instant.now();
        orders.insert(
                duplicate,
                first.customer().id,
                first.productId(),
                2990,
                "pay_" + UUID.randomUUID().toString().replace("-", ""),
                now.minusSeconds(60),
                now.plusSeconds(1800));
        assertThat(orders.markPaid(duplicate, now)).isTrue();
        return duplicate;
    }

    private int accessRows(UUID orderId) {
        return jdbc.queryForObject("select count(*) from accesses where order_id = ?", Integer.class, orderId);
    }

    private int accessRows(Placed placed) {
        return jdbc.queryForObject(
                "select count(*) from accesses where customer_id = ? and product_id = ?",
                Integer.class,
                placed.customer().id,
                placed.productId());
    }

    /** The provider answers this charge as refunded and sends the refund event; waits for the REFUNDED Order. */
    private void refundThroughTheProvider(Placed placed) throws Exception {
        gateway.answerCharge(
                placed.chargeId(),
                new PixCharge(
                        placed.chargeId(),
                        "REFUNDED",
                        new BigDecimal("29.90"),
                        "PIX",
                        placed.orderId().toString(),
                        false));
        assertThat(AsaasWebhookTestSupport.post(
                                port,
                                LOOPBACK,
                                Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN),
                                paymentEvent(uniqueId(), "PAYMENT_REFUNDED", placed.chargeId()),
                                "application/json")
                        .statusCode())
                .isEqualTo(200);
        await("the REFUNDED Order", () -> "REFUNDED".equals(status(placed.orderId())));
    }

    @Test
    @DisplayName(
            "FR-023: compra duplicada — a solicitação do Pedido que concedeu o Acesso suspende o Acesso e não toca o Pedido duplicado")
    void shouldSuspendTheAccessOfTheRequestedOrderAndLeaveTheDuplicatePurchaseAlone() throws Exception {
        Placed first = placePaidOrder();
        UUID duplicate = paidDuplicateOf(first);

        HttpResponse<String> response = request(first, "BOUGHT_BY_MISTAKE", null);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(suspended(first.orderId())).isTrue();
        assertThat(status(duplicate)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject(
                        "select count(*) from refund_requests where customer_id = ?",
                        Integer.class,
                        first.customer().id))
                .isEqualTo(1);
        assertThat(requestRows(first.orderId())).isEqualTo(1);
        assertThat(accessRows(duplicate)).isZero();

        refundThroughTheProvider(first);

        assertThat(jdbc.queryForObject(
                        "select cancelled_at is not null from accesses where order_id = ?",
                        Boolean.class,
                        first.orderId()))
                .isTrue();
        assertThat(status(duplicate)).isEqualTo("PAID");
        assertThat(accessRows(duplicate)).isZero();
    }

    @Test
    @DisplayName("FR-023: Pedido pago enquanto o Acesso está suspenso vira compra duplicada, sem Acesso novo")
    void shouldMarkAnOrderPaidWhileTheAccessIsSuspendedAsADuplicatePurchase() throws Exception {
        Placed first = placePaidOrder();
        assertThat(request(first, "NOT_AS_EXPECTED", null).statusCode()).isEqualTo(201);
        // the purchase is refused while the request is open (REFUND_IN_PROGRESS), so the second Order is inserted
        // straight, as a charge created before the request and paid late
        UUID second = UUID.randomUUID();
        String chargeId = "pay_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        orders.insert(
                second,
                first.customer().id,
                first.productId(),
                2990,
                chargeId,
                now.minusSeconds(60),
                now.plusSeconds(1800));
        gateway.answerCharge(
                chargeId,
                new PixCharge(chargeId, "RECEIVED", new BigDecimal("29.90"), "PIX", second.toString(), false));
        String eventId = uniqueId();

        assertThat(AsaasWebhookTestSupport.post(
                                port,
                                LOOPBACK,
                                Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN),
                                paymentEvent(eventId, "PAYMENT_RECEIVED", chargeId),
                                "application/json")
                        .statusCode())
                .isEqualTo(200);

        String resultSql = "select r.result from payment_event_results r join payment_events e"
                + " on e.id = r.payment_event_id where e.provider_event_id = ?";
        await(
                "the result of the payment event",
                () -> !jdbc.queryForList(resultSql, String.class, eventId).isEmpty());
        assertThat(jdbc.queryForList(resultSql, String.class, eventId)).containsExactly("DUPLICATE_PURCHASE");
        assertThat(status(second)).isEqualTo("PAID");
        assertThat(accessRows(first)).isEqualTo(1);
        assertThat(accessRows(second)).isZero();
        assertThat(suspended(first.orderId())).isTrue();
        assertThat(libraryItems(first.customer())).isEqualTo("0");
    }

    @Test
    @DisplayName("SEC-012: comentário com caractere nulo não derruba a solicitação")
    void shouldStoreTheDetailsWithoutTheNullCharacter() throws Exception {
        Placed placed = placePaidOrder();

        HttpResponse<String> response =
                request(placed.customer(), placed.orderId(), "{\"reason\":\"OTHER\",\"details\":\"a\\u0000b\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                        "select details from refund_requests where order_id = ?", String.class, placed.orderId()))
                .isEqualTo("ab");
    }

    @Test
    @DisplayName(
            "FR-023: 3ª solicitação em 12 meses — Alerta REFUND_REQUESTS_REPEATED; a 2ª não gera; um Reembolso de 13 meses atrás não conta")
    void shouldAlertOnTheThirdRefundOfTheCustomerWithinTwelveMonths() throws Exception {
        OrderHttp.Customer customer = OrderHttp.newCustomer(port, jdbc, encoder);
        Placed old = placePaidOrder(customer);
        Placed recent = placePaidOrder(customer);
        Placed second = placePaidOrder(customer);
        Placed third = placePaidOrder(customer);
        Instant now = Instant.now();
        jdbc.update(
                "update orders set status = 'REFUNDED', refunded_at = ? where id = ?",
                Timestamp.from(now.minus(Duration.ofDays(400))),
                old.orderId());
        jdbc.update(
                "update orders set status = 'REFUNDED', refunded_at = ? where id = ?",
                Timestamp.from(now.minus(Duration.ofDays(30))),
                recent.orderId());

        assertThat(request(second, "OTHER", null).statusCode()).isEqualTo(201);

        assertThat(alertRows("REFUND_REQUESTS_REPEATED", second.orderId())).isZero();

        assertThat(request(third, "OTHER", null).statusCode()).isEqualTo(201);

        assertThat(alertOccurrences("REFUND_REQUESTS_REPEATED", third.orderId()))
                .isEqualTo(1);
        assertThat(alertOccurrences("REFUND_REQUESTED", third.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "FR-023, FR-025: depois da solicitação, o estorno no Asaas conclui — REFUNDED, Acesso cancelado, e-mail enviado")
    void shouldFinishTheRefundThroughTheProviderAfterTheRequest() throws Exception {
        Placed placed = placePaidOrder();
        HttpResponse<String> requested = request(placed, "DEFECTIVE_FILE", null);
        assertThat(requested.statusCode()).isEqualTo(201);
        gateway.answerCharge(
                placed.chargeId(),
                new PixCharge(
                        placed.chargeId(),
                        "REFUNDED",
                        new BigDecimal("29.90"),
                        "PIX",
                        placed.orderId().toString(),
                        false));
        String eventId = uniqueId();

        assertThat(AsaasWebhookTestSupport.post(
                                port,
                                LOOPBACK,
                                Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN),
                                paymentEvent(eventId, "PAYMENT_REFUNDED", placed.chargeId()),
                                "application/json")
                        .statusCode())
                .isEqualTo(200);

        await("the REFUNDED Order", () -> "REFUNDED".equals(status(placed.orderId())));
        assertThat(jdbc.queryForObject(
                        "select cancelled_at is not null from accesses where order_id = ?",
                        Boolean.class,
                        placed.orderId()))
                .isTrue();
        assertThat(sender.awaitMessageTo(placed.customer().email, Duration.ofSeconds(15)))
                .isPresent()
                .get()
                .extracting(EmailMessage::subject)
                .isEqualTo(OrderRefundedEmail.SUBJECT);
        assertThat(requestRows(placed.orderId())).isEqualTo(1);

        // asking again after the refund reads the stored request back
        HttpResponse<String> again = request(placed, "DEFECTIVE_FILE", null);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(OrderHttp.json(again, "$.requestedAt")).isEqualTo(OrderHttp.json(requested, "$.requestedAt"));
    }

    @Test
    @DisplayName("FR-036: conta anonimizada não registra solicitação de Reembolso")
    void shouldRefuseTheRequestOfAnAnonymizedAccount() throws Exception {
        Placed placed = placePaidOrder();
        // straight in the database, so the HTTP session stays valid: the window of the race with the anonymization
        jdbc.update("update customers set anonymized_at = now() where id = ?", placed.customer().id);

        HttpResponse<String> response = request(placed, "DEFECTIVE_FILE", "O arquivo não abre");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(requestRows(placed.orderId())).isZero();
        assertThat(suspended(placed.orderId())).isFalse();
        assertThat(audits(placed.orderId())).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from admin_alerts where order_id = ?", Integer.class, placed.orderId()))
                .isZero();
    }

    @Test
    @DisplayName(
            "FR-036, FR-023: conta anonimizada com solicitação aberta — o comentário some, a linha fica e o estorno ainda conclui")
    void shouldFinishTheRefundAfterTheAccountIsAnonymizedWithAnOpenRequest() throws Exception {
        Placed placed = placePaidOrder();
        assertThat(request(placed, "DEFECTIVE_FILE", "O arquivo não abre").statusCode())
                .isEqualTo(201);
        Map<String, Object> before =
                jdbc.queryForMap("select * from refund_requests where order_id = ?", placed.orderId());
        assertThat(before.get("details")).isEqualTo("O arquivo não abre");

        assertThat(anonymization.anonymize(placed.customer().email, UUID.randomUUID(), LOOPBACK))
                .contains(placed.customer().id);

        Map<String, Object> after =
                jdbc.queryForMap("select * from refund_requests where order_id = ?", placed.orderId());
        assertThat(after.get("details")).isNull();
        assertThat(after.get("reason")).isEqualTo("DEFECTIVE_FILE");
        assertThat(after.get("requested_at")).isEqualTo(before.get("requested_at"));
        assertThat(after.get("id")).isEqualTo(before.get("id"));

        gateway.answerCharge(
                placed.chargeId(),
                new PixCharge(
                        placed.chargeId(),
                        "REFUNDED",
                        new BigDecimal("29.90"),
                        "PIX",
                        placed.orderId().toString(),
                        false));
        assertThat(AsaasWebhookTestSupport.post(
                                port,
                                LOOPBACK,
                                Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN),
                                paymentEvent(uniqueId(), "PAYMENT_REFUNDED", placed.chargeId()),
                                "application/json")
                        .statusCode())
                .isEqualTo(200);

        await("the REFUNDED Order", () -> "REFUNDED".equals(status(placed.orderId())));
        assertThat(jdbc.queryForObject(
                        "select cancelled_at is not null from accesses where order_id = ?",
                        Boolean.class,
                        placed.orderId()))
                .isTrue();
        assertThat(requestRows(placed.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-019: comprar o mesmo Produto com a solicitação aberta — 409 REFUND_IN_PROGRESS")
    void shouldRefuseToBuyTheSameProductWhileTheRefundIsInProgress() throws Exception {
        Placed placed = placePaidOrder();
        assertThat(request(placed, "BOUGHT_BY_MISTAKE", null).statusCode()).isEqualTo(201);

        HttpResponse<String> response = placed.customer().order(placed.productId(), CPF);

        assertRefusal(response, "order", "REFUND_IN_PROGRESS");
        assertThat(jdbc.queryForObject(
                        "select count(*) from orders where customer_id = ? and product_id = ?",
                        Integer.class,
                        placed.customer().id,
                        placed.productId()))
                .isEqualTo(1);
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
}
