package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** FR-024, SEC-011, SEC-027: a paid Order, the light status route and the refusal to buy what the Customer owns. */
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=paid-order-read", "app.login-limit.ip-max-attempts=1000"})
class PaidOrderReadTest {

    private static final String CPF = "52998224725";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SwitchablePixGateway gateway;

    private OrderHttp.Customer customer;
    private UUID productId;

    @BeforeEach
    void setUp() throws Exception {
        gateway.reset();
        customer = OrderHttp.newCustomer(port, jdbc, encoder);
        productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia de Contratos", 2990);
    }

    @AfterEach
    void cleanUp() {
        OrderTestSupport.deleteOrdersOf(jdbc, customer.id, productId);
    }

    private UUID newOrder() throws Exception {
        return UUID.fromString(OrderHttp.json(customer.order(productId, CPF), "$.id"));
    }

    private void markPaid(UUID orderId) {
        jdbc.update("update orders set status = 'PAID', paid_at = now() where id = ?", orderId);
    }

    private void grantAccess(UUID orderId) {
        jdbc.update(
                "insert into accesses (customer_id, product_id, order_id, granted_at) values (?, ?, ?, now())",
                customer.id,
                productId,
                orderId);
    }

    private Map<String, Object> row(UUID orderId) {
        return jdbc.queryForMap("select * from orders where id = ?", orderId);
    }

    @Test
    @DisplayName("FR-024: shouldShowAPaidOrderWithoutCallingTheProvider")
    void shouldShowAPaidOrderWithoutCallingTheProvider() throws Exception {
        UUID id = newOrder();
        markPaid(id);
        long reads = gateway.copyPasteReads();
        int calls = gateway.calls().size();

        HttpResponse<String> view = customer.get("/api/orders/" + id);

        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(OrderHttp.json(view, "$.status")).isEqualTo("PAID");
        assertThat(OrderHttp.json(view, "$.pixCopyPaste")).isNull();
        assertThat(OrderHttp.json(view, "$.secondsToExpire")).isEqualTo("0");
        assertThat(OrderHttp.json(view, "$.productTitle")).isEqualTo("Guia de Contratos");
        assertThat(view.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(gateway.copyPasteReads()).isEqualTo(reads);
        assertThat(gateway.calls()).hasSize(calls);
    }

    @ParameterizedTest(name = "{0}, expires in {1} s -> {2}")
    @CsvSource({
        "PENDING, 1800, PENDING",
        "PENDING, -60, EXPIRED",
        "EXPIRED, 1800, EXPIRED",
        "PAID, 1800, PAID",
    })
    @DisplayName("FR-024: shouldAnswerTheStatusWithoutCallingTheProvider")
    void shouldAnswerTheStatusWithoutCallingTheProvider(String stored, int secondsLeft, String shown) throws Exception {
        UUID id = newOrder();
        jdbc.update("update orders set expires_at = now() + make_interval(secs => ?) where id = ?", secondsLeft, id);
        if (stored.equals("PAID")) {
            markPaid(id);
        } else if (stored.equals("EXPIRED")) {
            jdbc.update("update orders set status = 'EXPIRED', expired_at = now() where id = ?", id);
        }
        int calls = gateway.calls().size();

        HttpResponse<String> answer = customer.get("/api/orders/" + id + "/status");

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(OrderHttp.json(answer, "$.status")).isEqualTo(shown);
        assertThat(OrderHttp.json(answer, "$.length()")).isEqualTo("1");
        assertThat(answer.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(gateway.calls()).hasSize(calls);
    }

    @Test
    @DisplayName("SEC-010: shouldRefuseTheStatusToAnAnonymousVisitor")
    void shouldRefuseTheStatusToAnAnonymousVisitor() throws Exception {
        UUID id = newOrder();

        HttpResponse<String> answer = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders/" + id + "/status"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(answer.statusCode()).isEqualTo(401);
        assertThat(answer.body()).doesNotContain("PENDING");
    }

    @Test
    @DisplayName("SEC-011: shouldAnswerNotFoundForTheStatusOfAnotherCustomersOrder")
    void shouldAnswerNotFoundForTheStatusOfAnotherCustomersOrder() throws Exception {
        UUID id = newOrder();
        OrderHttp.Customer other = OrderHttp.newCustomer(port, jdbc, encoder);

        HttpResponse<String> answer = other.get("/api/orders/" + id + "/status");

        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.body()).doesNotContain("PENDING").doesNotContain(id.toString());
        assertThat(other.get("/api/orders/" + UUID.randomUUID() + "/status").statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("SEC-011: shouldAnswerNotFoundForAMalformedOrderId")
    void shouldAnswerNotFoundForAMalformedOrderId() throws Exception {
        HttpResponse<String> answer = customer.get("/api/orders/not-a-uuid/status");

        assertThat(answer.statusCode()).isIn(400, 404);
        assertThat(answer.body()).doesNotContain("PENDING").doesNotContain("Exception");
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("SEC-027: shouldNeverChangeAnOrderThroughACustomerRoute")
    void shouldNeverChangeAnOrderThroughACustomerRoute(String method) throws Exception {
        UUID id = newOrder();
        Map<String, Object> before = row(id);

        for (String path : List.of("/api/orders/" + id, "/api/orders/" + id + "/status")) {
            HttpResponse<String> answer = customer.send(method, path, "{\"status\":\"PAID\"}");
            assertThat(answer.statusCode()).as("%s %s", method, path).isIn(403, 405);
        }

        assertThat(row(id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from accesses where customer_id = ?", Long.class, customer.id))
                .isZero();
    }

    @Test
    @DisplayName("SEC-027: shouldKeepAPendingOrderPendingNoMatterHowOftenItIsRead")
    void shouldKeepAPendingOrderPendingNoMatterHowOftenItIsRead() throws Exception {
        UUID id = newOrder();
        Map<String, Object> before = row(id);

        for (int i = 0; i < 50; i++) {
            assertThat(OrderHttp.json(customer.get("/api/orders/" + id + "?status=PAID"), "$.status"))
                    .isEqualTo("PENDING");
            assertThat(OrderHttp.json(customer.get("/api/orders/" + id + "/status?status=PAID"), "$.status"))
                    .isEqualTo("PENDING");
        }

        assertThat(row(id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from accesses where customer_id = ?", Long.class, customer.id))
                .isZero();
    }

    @Test
    @DisplayName("FR-024: shouldRefuseToBuyAProductTheCustomerAlreadyHas")
    void shouldRefuseToBuyAProductTheCustomerAlreadyHas() throws Exception {
        UUID id = newOrder();
        markPaid(id);
        grantAccess(id);
        long orders = OrderTestSupport.countOrdersOf(jdbc, customer.id);
        long charges = gateway.count("CREATE_CHARGE");

        HttpResponse<String> answer = customer.order(productId, CPF);

        assertThat(answer.statusCode()).isEqualTo(409);
        assertThat(OrderHttp.json(answer, "$.errors.order")).isEqualTo("ALREADY_OWNED");
        assertThat(OrderTestSupport.countOrdersOf(jdbc, customer.id)).isEqualTo(orders);
        assertThat(gateway.count("CREATE_CHARGE")).isEqualTo(charges);
    }

    @Test
    @DisplayName("FR-024: shouldLetAnotherCustomerBuyTheSameProduct")
    void shouldLetAnotherCustomerBuyTheSameProduct() throws Exception {
        UUID id = newOrder();
        markPaid(id);
        grantAccess(id);
        OrderHttp.Customer other = OrderHttp.newCustomer(port, jdbc, encoder);

        assertThat(other.order(productId, CPF).statusCode()).isEqualTo(201);
    }
}
