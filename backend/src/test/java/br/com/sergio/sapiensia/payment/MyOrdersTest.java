package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
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

/** FR-020, SEC-011: the Customer's list of Orders. */
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=my-orders", "app.login-limit.ip-max-attempts=1000"})
class MyOrdersTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SwitchablePixGateway gateway;

    private OrderHttp.Customer customer;
    private OrderHttp.Customer other;
    private UUID paidProduct;
    private UUID expiredProduct;
    private UUID pendingProduct;

    @BeforeEach
    void setUp() throws Exception {
        gateway.reset();
        customer = OrderHttp.newCustomer(port, jdbc, encoder);
        other = OrderHttp.newCustomer(port, jdbc, encoder);
        paidProduct = OrderTestSupport.insertPublishedProduct(jdbc, "Guia Pago", 2990);
        expiredProduct = OrderTestSupport.insertPublishedProduct(jdbc, "Guia Expirado", 1990);
        pendingProduct = OrderTestSupport.insertPublishedProduct(jdbc, "Guia Pendente", 4990);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from refund_requests where customer_id in (?, ?)", customer.id, other.id);
        OrderTestSupport.deleteOrdersOf(jdbc, customer.id, paidProduct, expiredProduct, pendingProduct);
        OrderTestSupport.deleteOrdersOf(jdbc, other.id, paidProduct, expiredProduct, pendingProduct);
    }

    /** Inserts an Order; the offsets are in minutes from now. */
    private UUID insertOrder(
            UUID customerId, UUID productId, int priceCents, String status, int createdMinutes, int expiresMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at, paid_at, expired_at)"
                        + " values (?, ?, ?, ?, ?, ?, now() + make_interval(mins => ?),"
                        + " now() + make_interval(mins => ?),"
                        + " case when ? = 'PAID' then now() end, case when ? = 'EXPIRED' then now() end)",
                id,
                customerId,
                productId,
                priceCents,
                status,
                "pay_" + UUID.randomUUID(),
                createdMinutes,
                expiresMinutes,
                status,
                status);
        return id;
    }

    private HttpResponse<String> list(OrderHttp.Customer who) throws Exception {
        HttpResponse<String> answer = who.get("/api/orders");
        assertThat(answer.statusCode()).isEqualTo(200);
        return answer;
    }

    @Test
    @DisplayName("FR-020: shouldListTheCustomersOrdersNewestFirst")
    void shouldListTheCustomersOrdersNewestFirst() throws Exception {
        UUID paid = insertOrder(customer.id, paidProduct, 2990, "PAID", -300, -270);
        UUID expired = insertOrder(customer.id, expiredProduct, 1990, "EXPIRED", -200, -170);
        UUID pending = insertOrder(customer.id, pendingProduct, 4990, "PENDING", -5, 25);

        HttpResponse<String> answer = list(customer);

        assertThat(OrderHttp.json(answer, "$.items.length()")).isEqualTo("3");
        assertThat(OrderHttp.json(answer, "$.items[0].id")).isEqualTo(pending.toString());
        assertThat(OrderHttp.json(answer, "$.items[0].productTitle")).isEqualTo("Guia Pendente");
        assertThat(OrderHttp.json(answer, "$.items[0].priceCents")).isEqualTo("4990");
        assertThat(OrderHttp.json(answer, "$.items[0].status")).isEqualTo("PENDING");
        assertThat(OrderHttp.json(answer, "$.items[1].id")).isEqualTo(expired.toString());
        assertThat(OrderHttp.json(answer, "$.items[1].productTitle")).isEqualTo("Guia Expirado");
        assertThat(OrderHttp.json(answer, "$.items[1].priceCents")).isEqualTo("1990");
        assertThat(OrderHttp.json(answer, "$.items[1].status")).isEqualTo("EXPIRED");
        assertThat(OrderHttp.json(answer, "$.items[2].id")).isEqualTo(paid.toString());
        assertThat(OrderHttp.json(answer, "$.items[2].productTitle")).isEqualTo("Guia Pago");
        assertThat(OrderHttp.json(answer, "$.items[2].priceCents")).isEqualTo("2990");
        assertThat(OrderHttp.json(answer, "$.items[2].status")).isEqualTo("PAID");
        assertThat(OrderHttp.json(answer, "$.items[0].createdAt")).isNotBlank();
        assertThat(OrderHttp.json(answer, "$.items[0].expiresAt")).isNotBlank();
    }

    @Test
    @DisplayName("FR-020: shouldShowAPendingOrderPastItsTimeAsExpiredWithoutChangingIt")
    void shouldShowAPendingOrderPastItsTimeAsExpiredWithoutChangingIt() throws Exception {
        UUID id = insertOrder(customer.id, pendingProduct, 4990, "PENDING", -60, -30);

        HttpResponse<String> answer = list(customer);

        assertThat(OrderHttp.json(answer, "$.items[0].status")).isEqualTo("EXPIRED");
        Map<String, Object> row = jdbc.queryForMap("select status, expired_at from orders where id = ?", id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("expired_at")).isNull();
    }

    private void requestRefund(UUID customerId, UUID orderId) {
        jdbc.update(
                "insert into refund_requests (id, order_id, customer_id, reason, details, requested_at)"
                        + " values (?, ?, ?, 'OTHER', null, now())",
                UUID.randomUUID(),
                orderId,
                customerId);
    }

    @Test
    @DisplayName("FR-020: Pedido pago dentro do prazo traz refundableUntil")
    void shouldShowTheLastRefundDayOfAPaidOrderInsideTheWindow() throws Exception {
        UUID paid = insertOrder(customer.id, paidProduct, 2990, "PAID", -300, -270);
        String expected = jdbc.queryForObject(
                "select to_char((paid_at at time zone 'America/Sao_Paulo')::date + 7, 'YYYY-MM-DD')"
                        + " from orders where id = ?",
                String.class,
                paid);

        HttpResponse<String> answer = list(customer);

        assertThat(OrderHttp.json(answer, "$.items[0].refundableUntil")).isEqualTo(expected);
        assertThat(answer.body()).contains("\"refundableUntil\":\"" + expected + "\"");
        assertThat(OrderHttp.json(answer, "$.items[0].refundRequestedAt")).isNull();
    }

    @Test
    @DisplayName("FR-020: Pedido pago fora do prazo traz refundableUntil nulo")
    void shouldShowNoRefundDayWhenTheWindowIsClosed() throws Exception {
        UUID paid = insertOrder(customer.id, paidProduct, 2990, "PAID", -300, -270);
        jdbc.update("update orders set paid_at = now() - interval '10 days' where id = ?", paid);

        HttpResponse<String> answer = list(customer);

        assertThat(OrderHttp.json(answer, "$.items[0].status")).isEqualTo("PAID");
        assertThat(OrderHttp.json(answer, "$.items[0].refundableUntil")).isNull();
        assertThat(OrderHttp.json(answer, "$.items[0].refundRequestedAt")).isNull();
    }

    @Test
    @DisplayName("FR-020: Pedido pago com solicitação traz refundRequestedAt e refundableUntil nulo")
    void shouldShowTheRequestDateAndNoRefundDayWhenRequested() throws Exception {
        UUID paid = insertOrder(customer.id, paidProduct, 2990, "PAID", -300, -270);
        requestRefund(customer.id, paid);

        HttpResponse<String> answer = list(customer);

        assertThat(OrderHttp.json(answer, "$.items[0].refundableUntil")).isNull();
        assertThat(OrderHttp.json(answer, "$.items[0].refundRequestedAt")).isNotBlank();
    }

    @Test
    @DisplayName("FR-020: Pedido reembolsado traz os dois campos nulos")
    void shouldShowNeitherFieldOnARefundedOrder() throws Exception {
        UUID paid = insertOrder(customer.id, paidProduct, 2990, "PAID", -300, -270);
        requestRefund(customer.id, paid);
        jdbc.update("update orders set status = 'REFUNDED', refunded_at = now() where id = ?", paid);

        HttpResponse<String> answer = list(customer);

        assertThat(OrderHttp.json(answer, "$.items[0].status")).isEqualTo("REFUNDED");
        assertThat(OrderHttp.json(answer, "$.items[0].refundableUntil")).isNull();
        assertThat(OrderHttp.json(answer, "$.items[0].refundRequestedAt")).isNull();
    }

    @Test
    @DisplayName("FR-020: shouldNeverCallThePaymentProvider")
    void shouldNeverCallThePaymentProvider() throws Exception {
        insertOrder(customer.id, pendingProduct, 4990, "PENDING", -5, 25);
        int before = gateway.calls().size();

        HttpResponse<String> answer = list(customer);

        assertThat(OrderHttp.json(answer, "$.items.length()")).isEqualTo("1");
        assertThat(gateway.calls()).hasSize(before);
    }

    @Test
    @DisplayName("FR-020: shouldListEveryOrderOfTheSameProduct")
    void shouldListEveryOrderOfTheSameProduct() throws Exception {
        insertOrder(customer.id, paidProduct, 2990, "EXPIRED", -120, -90);
        insertOrder(customer.id, paidProduct, 2990, "PAID", -30, -5);

        HttpResponse<String> answer = list(customer);

        assertThat(OrderHttp.json(answer, "$.items.length()")).isEqualTo("2");
        assertThat(OrderHttp.json(answer, "$.items[0].status")).isEqualTo("PAID");
        assertThat(OrderHttp.json(answer, "$.items[1].status")).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("FR-020: shouldKeepTheOrderOfAnUnpublishedProduct")
    void shouldKeepTheOrderOfAnUnpublishedProduct() throws Exception {
        insertOrder(customer.id, paidProduct, 2990, "PAID", -30, -5);
        jdbc.update("update products set status = 'UNPUBLISHED' where id = ?", paidProduct);

        HttpResponse<String> answer = list(customer);

        assertThat(OrderHttp.json(answer, "$.items.length()")).isEqualTo("1");
        assertThat(OrderHttp.json(answer, "$.items[0].productTitle")).isEqualTo("Guia Pago");
    }

    @Test
    @DisplayName("FR-020: shouldAnswerAnEmptyListForACustomerWithoutOrders")
    void shouldAnswerAnEmptyListForACustomerWithoutOrders() throws Exception {
        HttpResponse<String> answer = list(customer);

        assertThat(OrderHttp.json(answer, "$.items.length()")).isEqualTo("0");
    }

    @Test
    @DisplayName("FR-020, SEC-011: shouldNeverListAnotherCustomersOrders")
    void shouldNeverListAnotherCustomersOrders() throws Exception {
        UUID mine = insertOrder(customer.id, paidProduct, 2990, "PAID", -30, -5);
        UUID theirs = insertOrder(other.id, expiredProduct, 1990, "EXPIRED", -60, -30);

        HttpResponse<String> mineAnswer = list(customer);
        HttpResponse<String> theirAnswer = list(other);

        assertThat(OrderHttp.json(mineAnswer, "$.items.length()")).isEqualTo("1");
        assertThat(OrderHttp.json(mineAnswer, "$.items[0].id")).isEqualTo(mine.toString());
        assertThat(mineAnswer.body()).doesNotContain(theirs.toString());
        assertThat(OrderHttp.json(theirAnswer, "$.items.length()")).isEqualTo("1");
        assertThat(OrderHttp.json(theirAnswer, "$.items[0].id")).isEqualTo(theirs.toString());
        assertThat(theirAnswer.body()).doesNotContain(mine.toString());
    }

    @Test
    @DisplayName("FR-020: shouldNotExposeThePixCodeOrTheProviderId")
    void shouldNotExposeThePixCodeOrTheProviderId() throws Exception {
        insertOrder(customer.id, pendingProduct, 4990, "PENDING", -5, 25);

        HttpResponse<String> answer = list(customer);

        Map<String, Object> item = JsonPath.read(answer.body(), "$.items[0]");
        assertThat(item.keySet())
                .containsExactlyInAnyOrderElementsOf(List.of(
                        "id",
                        "productTitle",
                        "priceCents",
                        "status",
                        "createdAt",
                        "expiresAt",
                        "refundableUntil",
                        "refundRequestedAt"));
        assertThat(answer.body()).doesNotContain("pay_");
    }
}
