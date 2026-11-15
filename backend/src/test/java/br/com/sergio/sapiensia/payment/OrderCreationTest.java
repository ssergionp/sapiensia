package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** FR-019, SEC-010..012, SEC-033: creating and reading an Order against the real server, database and fake gateway. */
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=orders", "app.login-limit.ip-max-attempts=1000"})
class OrderCreationTest {

    private static final String CPF = "52998224725";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SwitchablePixGateway gateway;

    @Autowired
    Orders orders;

    @Autowired
    Accesses accesses;

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

    private UUID insertProduct(String status, String title) {
        return jdbc.queryForObject(
                "insert into products (title, description, category, price_cents, status, first_published_at)"
                        + " values (?, 'd', 'LAW', 1990, ?, case when ? = 'DRAFT' then null else now() end)"
                        + " returning id",
                UUID.class,
                title,
                status,
                status);
    }

    @Test
    @DisplayName("FR-019: shouldCreateAPendingOrderWithItsOwnIdAndThePaymentId")
    void shouldCreateAPendingOrderWithItsOwnIdAndThePaymentId() throws Exception {
        HttpResponse<String> created = customer.order(productId, CPF);

        assertThat(created.statusCode()).isEqualTo(201);
        String id = OrderHttp.json(created, "$.id");
        assertThat(created.headers().firstValue("Location")).contains("/api/orders/" + id);
        assertThat(created.body()).doesNotContain("pixCopyPaste").doesNotContain("PIX-FICTICIO");
        assertThat(OrderHttp.json(created, "$.length()")).isEqualTo("1");

        HttpResponse<String> view = customer.get("/api/orders/" + id);
        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(OrderHttp.json(view, "$.status")).isEqualTo("PENDING");
        assertThat(Long.parseLong(OrderHttp.json(view, "$.secondsToExpire"))).isBetween(1795L, 1800L);
        assertThat(OrderHttp.json(view, "$.pixCopyPaste")).isNotBlank();
        assertThat(OrderHttp.json(view, "$.priceCents")).isEqualTo("2990");
        assertThat(OrderHttp.json(view, "$.productTitle")).isEqualTo("Guia de Contratos");

        String paymentId =
                jdbc.queryForObject("select provider_payment_id from orders where id = ?::uuid", String.class, id);
        assertThat(paymentId).startsWith("pay_fake_");
        assertThat(gateway.lastChargeOrderId()).hasToString(id);
        assertThat(gateway.lastCustomerName()).isEqualTo(customer.email);
    }

    @Test
    @DisplayName("FR-025: depois do cancelamento a compra responde 201, não 409")
    void shouldAcceptANewOrderAfterTheAccessWasCancelled() throws Exception {
        String first = OrderHttp.json(customer.order(productId, CPF), "$.id");
        UUID firstId = UUID.fromString(first);
        Instant now = Instant.now();
        orders.markPaid(firstId, now);
        accesses.grant(customer.id, productId, firstId, now);
        assertThat(customer.order(productId, CPF).statusCode()).isEqualTo(409);

        assertThat(orders.markRefunded(firstId, now)).isTrue();
        assertThat(accesses.cancelByOrder(firstId, now)).isTrue();

        HttpResponse<String> again = customer.order(productId, CPF);
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(OrderHttp.json(again, "$.id")).isNotEqualTo(first);
    }

    @Test
    @DisplayName("ADR-0003: shouldReadThePixCodeFromTheProviderOnEveryView")
    void shouldReadThePixCodeFromTheProviderOnEveryView() throws Exception {
        String id = OrderHttp.json(customer.order(productId, CPF), "$.id");
        assertThat(gateway.copyPasteReads()).isZero();

        String first = OrderHttp.json(customer.get("/api/orders/" + id), "$.pixCopyPaste");
        String second = OrderHttp.json(customer.get("/api/orders/" + id), "$.pixCopyPaste");
        String third = OrderHttp.json(customer.get("/api/orders/" + id), "$.pixCopyPaste");

        assertThat(gateway.copyPasteReads()).isEqualTo(3);
        assertThat(first).isNotBlank().isEqualTo(second).isEqualTo(third);
    }

    @Test
    @DisplayName("SEC-012: shouldChargeTheServerPriceWhateverTheBodySays")
    void shouldChargeTheServerPriceWhateverTheBodySays() throws Exception {
        String body = "{\"productId\":\"" + productId + "\",\"cpf\":\"" + CPF + "\","
                + "\"priceCents\":1,\"value\":0.01,\"amount\":1,\"status\":\"PAID\"}";

        HttpResponse<String> created = customer.post("/api/orders", body);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(gateway.lastChargeAmountCents()).isEqualTo(2990);
        Integer stored =
                jdbc.queryForObject("select price_cents from orders where customer_id = ?", Integer.class, customer.id);
        assertThat(stored).isEqualTo(2990);
        assertThat(jdbc.queryForObject("select status from orders where customer_id = ?", String.class, customer.id))
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("SEC-012: shouldRefuseADraftAnUnpublishedAndAMissingProductAlike")
    void shouldRefuseADraftAnUnpublishedAndAMissingProductAlike() throws Exception {
        List<UUID> ids =
                List.of(insertProduct("DRAFT", "Rascunho"), insertProduct("UNPUBLISHED", "Fora"), UUID.randomUUID());

        List<HttpResponse<String>> answers = new java.util.ArrayList<>();
        for (UUID id : ids) {
            answers.add(customer.order(id, CPF));
        }

        for (HttpResponse<String> answer : answers) {
            assertThat(answer.statusCode()).isEqualTo(404);
            assertThat(answer.body()).isEmpty();
            assertThat(answer.headers().map().keySet())
                    .containsExactlyInAnyOrderElementsOf(
                            answers.get(0).headers().map().keySet());
        }
        assertThat(gateway.calls()).isEmpty();
    }

    @Test
    @DisplayName("shouldRefuseABodyWithoutAProductId")
    void shouldRefuseABodyWithoutAProductId() throws Exception {
        assertThat(customer.post("/api/orders", "{}").statusCode()).isEqualTo(400);
        assertThat(customer.post("/api/orders", "{\"productId\":\"not-a-uuid\"}")
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("shouldRefuseAMalformedId")
    void shouldRefuseAMalformedId() throws Exception {
        assertThat(customer.get("/api/orders/not-a-uuid").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("SEC-033: shouldAskForTheCpfOnlyOnTheFirstPurchase")
    void shouldAskForTheCpfOnlyOnTheFirstPurchase() throws Exception {
        UUID second = OrderTestSupport.insertPublishedProduct(jdbc, "Outro Guia", 1990);

        HttpResponse<String> without = customer.order(productId);
        assertThat(without.statusCode()).isEqualTo(400);
        assertThat(OrderHttp.json(without, "$.errors.cpf")).isEqualTo("CPF_REQUIRED");
        assertThat(OrderTestSupport.countOrdersOf(jdbc, customer.id)).isZero();

        assertThat(customer.order(productId, CPF).statusCode()).isEqualTo(201);
        assertThat(gateway.lastCustomerCpf()).isEqualTo(CPF);
        assertThat(gateway.count("CREATE_CUSTOMER")).isEqualTo(1);

        assertThat(customer.order(second).statusCode()).isEqualTo(201);
        assertThat(gateway.count("CREATE_CUSTOMER")).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-033: shouldRefuseAnInvalidCpfWithoutEchoingIt")
    void shouldRefuseAnInvalidCpfWithoutEchoingIt() throws Exception {
        for (String bad :
                List.of("52998224724", "11111111111", "5299822472", "529982247250", "abcdefghijk", "<b>x</b>")) {
            HttpResponse<String> answer = customer.order(productId, bad);

            assertThat(answer.statusCode()).as(bad).isEqualTo(400);
            assertThat(OrderHttp.json(answer, "$.errors.cpf")).as(bad).isEqualTo("CPF_INVALID");
            assertThat(answer.body()).as(bad).doesNotContain(bad);
        }
        assertThat(gateway.count("CREATE_CUSTOMER")).isZero();
    }

    @Test
    @DisplayName("SEC-033: shouldTreatACpfRejectedByTheProviderAsInvalid")
    void shouldTreatACpfRejectedByTheProviderAsInvalid() throws Exception {
        gateway.failAt("CREATE_CUSTOMER", PixGatewayException.Kind.DOCUMENT_REJECTED);

        HttpResponse<String> answer = customer.order(productId, CPF);

        assertThat(answer.statusCode()).isEqualTo(400);
        assertThat(OrderHttp.json(answer, "$.errors.cpf")).isEqualTo("CPF_INVALID");
        assertThat(answer.body()).doesNotContain(CPF);
    }

    @Test
    @DisplayName("SEC-033: shouldReuseACustomerThatAlreadyExistsAtTheProvider")
    void shouldReuseACustomerThatAlreadyExistsAtTheProvider() throws Exception {
        gateway.createCustomer(customer.id, "x", CPF);
        gateway.reset();

        HttpResponse<String> answer = customer.order(productId);

        assertThat(answer.statusCode()).isEqualTo(201);
        assertThat(gateway.count("CREATE_CUSTOMER")).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from payment_customers where customer_id = ?", Long.class, customer.id))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-011: shouldAnswer404ForAnotherCustomersOrder")
    void shouldAnswer404ForAnotherCustomersOrder() throws Exception {
        String id = OrderHttp.json(customer.order(productId, CPF), "$.id");
        OrderHttp.Customer other = OrderHttp.newCustomer(port, jdbc, encoder);

        HttpResponse<String> foreign = other.get("/api/orders/" + id);
        HttpResponse<String> unknown = other.get("/api/orders/" + UUID.randomUUID());

        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).isEmpty();
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).isEmpty();
        assertThat(foreign.headers().map().keySet())
                .containsExactlyInAnyOrderElementsOf(unknown.headers().map().keySet());
    }

    @Test
    @DisplayName("FR-019: shouldReadItsOwnOrder")
    void shouldReadItsOwnOrder() throws Exception {
        String id = OrderHttp.json(customer.order(productId, CPF), "$.id");

        HttpResponse<String> view = customer.get("/api/orders/" + id);

        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(OrderHttp.json(view, "$.id")).isEqualTo(id);
        assertThat(OrderHttp.json(view, "$.productId")).isEqualTo(productId.toString());
        assertThat(OrderHttp.json(view, "$.expiresAt")).isNotBlank();
    }

    @Test
    @DisplayName("FR-019: shouldNeverCacheAnOrderResponse")
    void shouldNeverCacheAnOrderResponse() throws Exception {
        String id = OrderHttp.json(customer.order(productId, CPF), "$.id");

        HttpResponse<String> view = customer.get("/api/orders/" + id);

        assertThat(view.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    }

    @Test
    @DisplayName("FR-019: shouldShowAnOrderPastItsExpiryAsExpiredWithoutCallingTheProvider")
    void shouldShowAnOrderPastItsExpiryAsExpiredWithoutCallingTheProvider() throws Exception {
        String id = OrderHttp.json(customer.order(productId, CPF), "$.id");
        jdbc.update("update orders set expires_at = now() - interval '1 minute' where id = ?::uuid", id);
        gateway.reset();

        HttpResponse<String> view = customer.get("/api/orders/" + id);

        assertThat(view.statusCode()).isEqualTo(200);
        assertThat(OrderHttp.json(view, "$.status")).isEqualTo("EXPIRED");
        assertThat(OrderHttp.json(view, "$.secondsToExpire")).isEqualTo("0");
        assertThat(OrderHttp.json(view, "$.pixCopyPaste")).isNull();
        assertThat(gateway.calls()).isEmpty();
    }
}
