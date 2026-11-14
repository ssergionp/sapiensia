package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
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

/** FR-019, ADR-0003: one pending Order per Customer and Product, one charge, and no charge left without an Order. */
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=orders", "app.login-limit.ip-max-attempts=1000"})
class OrderIdempotencyTest {

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

    private long orderRows(OrderHttp.Customer buyer) {
        return OrderTestSupport.countOrdersOf(jdbc, buyer.id);
    }

    @Test
    @DisplayName("FR-019: shouldReturnTheSamePendingOrderWhenTheRequestIsRepeated")
    void shouldReturnTheSamePendingOrderWhenTheRequestIsRepeated() throws Exception {
        HttpResponse<String> first = customer.order(productId, CPF);
        HttpResponse<String> second = customer.order(productId, CPF);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(OrderHttp.json(second, "$.id")).isEqualTo(OrderHttp.json(first, "$.id"));
        assertThat(gateway.count("CREATE_CHARGE")).isEqualTo(1);
        assertThat(orderRows(customer)).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-019: shouldCreateOneChargeForTwoSimultaneousRequests")
    void shouldCreateOneChargeForTwoSimultaneousRequests() throws Exception {
        for (int round = 0; round < 20; round++) {
            gateway.reset();
            OrderTestSupport.deleteOrdersOfProducts(jdbc, productId);
            OrderHttp.Customer buyer = OrderHttp.newCustomer(port, jdbc, encoder);
            CountDownLatch release = new CountDownLatch(1);
            gateway.holdNextCharge(release);

            CompletableFuture<HttpResponse<String>> firstRequest = CompletableFuture.supplyAsync(() -> send(buyer));
            long deadline = System.currentTimeMillis() + 15_000;
            while (gateway.count("CREATE_CHARGE") == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(gateway.count("CREATE_CHARGE")).as("round " + round).isEqualTo(1);

            HttpResponse<String> second = buyer.order(productId, CPF);
            release.countDown();
            HttpResponse<String> first = firstRequest.get();

            assertThat(second.statusCode()).as("round " + round).isEqualTo(409);
            assertThat(OrderHttp.json(second, "$.errors.order")).isEqualTo("ORDER_IN_PROGRESS");
            assertThat(first.statusCode()).as("round " + round).isEqualTo(201);
            assertThat(orderRows(buyer)).isEqualTo(1);
            assertThat(gateway.count("CREATE_CHARGE")).isEqualTo(1);
        }
    }

    private HttpResponse<String> send(OrderHttp.Customer buyer) {
        try {
            return buyer.order(productId, CPF);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("FR-019: shouldCreateSeparateOrdersForDifferentProducts")
    void shouldCreateSeparateOrdersForDifferentProducts() throws Exception {
        UUID other = OrderTestSupport.insertPublishedProduct(jdbc, "Outro Guia", 1990);

        HttpResponse<String> first = customer.order(productId, CPF);
        HttpResponse<String> second = customer.order(other);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(OrderHttp.json(second, "$.id")).isNotEqualTo(OrderHttp.json(first, "$.id"));
        assertThat(gateway.count("CREATE_CHARGE")).isEqualTo(2);
    }

    @Test
    @DisplayName("FR-019: shouldLetTwoCustomersBuyTheSameProduct")
    void shouldLetTwoCustomersBuyTheSameProduct() throws Exception {
        OrderHttp.Customer other = OrderHttp.newCustomer(port, jdbc, encoder);

        assertThat(customer.order(productId, CPF).statusCode()).isEqualTo(201);
        assertThat(other.order(productId, CPF).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from orders where product_id = ?", Long.class, productId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("ADR-0003: shouldCancelTheChargeWhenTheOrderCannotBeStored")
    void shouldCancelTheChargeWhenTheOrderCannotBeStored() throws Exception {
        gateway.afterNextCharge(() -> insertConcurrentPendingOrder(customer.id));

        HttpResponse<String> answer = customer.order(productId, CPF);

        assertThat(answer.statusCode()).isEqualTo(409);
        assertThat(OrderHttp.json(answer, "$.errors.order")).isEqualTo("ORDER_IN_PROGRESS");
        assertThat(gateway.cancelledCharges()).containsExactly(gateway.lastChargeId());
        assertThat(orderRows(customer)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-0003: shouldAnswerTheSameWhenTheCancelAlsoFails")
    void shouldAnswerTheSameWhenTheCancelAlsoFails(CapturedOutput output) throws Exception {
        gateway.failCancel(true);
        gateway.afterNextCharge(() -> insertConcurrentPendingOrder(customer.id));

        HttpResponse<String> answer = customer.order(productId, CPF);

        assertThat(answer.statusCode()).isEqualTo(409);
        assertThat(OrderHttp.json(answer, "$.errors.order")).isEqualTo("ORDER_IN_PROGRESS");
        assertThat(orderRows(customer)).isEqualTo(1);
        assertThat(output.getAll()).contains("Pix charge left without an Order: chargeId=" + gateway.lastChargeId());
    }

    /** From another thread, so it uses another connection and commits on its own. */
    private void insertConcurrentPendingOrder(UUID customerId) {
        CompletableFuture.runAsync(() -> jdbc.update(
                        "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                                + " created_at, expires_at)"
                                + " values (gen_random_uuid(), ?, ?, 2990, 'PENDING', ?, now(),"
                                + " now() + interval '30 minutes')",
                        customerId,
                        productId,
                        "pay_other_" + UUID.randomUUID()))
                .join();
    }
}
