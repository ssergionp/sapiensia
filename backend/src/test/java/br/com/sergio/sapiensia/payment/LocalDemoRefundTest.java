package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.TOKEN;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.paymentEvent;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The demo-only refund route (#132): marks a fake charge as REFUNDED so the real refund flow can run locally. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalDemoRefundTest {

    private static final Map<String, String> AUTH = Map.of(AsaasWebhookAccess.TOKEN_HEADER, TOKEN);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    PixGateway gateway;

    private HttpResponse<String> refundRoute(String chargeId, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/local-demo/charges/" + chargeId + "/refund"))
                .POST(HttpRequest.BodyPublishers.noBody());
        headers.forEach(builder::header);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> webhook(String type, String chargeId) throws Exception {
        return AsaasWebhookTestSupport.post(
                port, "127.0.0.1", AUTH, paymentEvent(uniqueId(), type, chargeId), "application/json");
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    @Test
    void shouldRefuseTheDemoRefundWithoutTheWebhookToken() throws Exception {
        String chargeId = gateway.createCharge(
                "cus_x", UUID.randomUUID(), 2990, java.time.LocalDate.now().plusDays(1), "demo");

        HttpResponse<String> response = refundRoute(chargeId, Map.of());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(gateway.readCharge(chargeId).status()).isEqualTo("RECEIVED");
    }

    @Test
    void shouldAnswer404ForAnUnknownCharge() throws Exception {
        assertThat(refundRoute("pay_fake_unknown", AUTH).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("FR-023: na demonstração, o estorno marcado no Fake e o webhook PAYMENT_REFUNDED cancelam o Acesso")
    void shouldRefundAPaidOrderThroughTheRealWebhook() throws Exception {
        OrderHttp.Customer customer = OrderHttp.newCustomer(port, jdbc, encoder);
        UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), 2990);
        HttpResponse<String> created = customer.order(productId, "52998224725");
        assertThat(created.statusCode()).isEqualTo(201);
        UUID orderId = UUID.fromString(OrderHttp.json(created, "$.id"));
        String chargeId =
                jdbc.queryForObject("select provider_payment_id from orders where id = ?", String.class, orderId);

        assertThat(webhook("PAYMENT_RECEIVED", chargeId).statusCode()).isEqualTo(200);
        await("the Order to be paid", () -> "PAID".equals(status(orderId)));

        assertThat(refundRoute(chargeId, AUTH).statusCode()).isEqualTo(204);
        assertThat(gateway.readCharge(chargeId).status()).isEqualTo("REFUNDED");
        assertThat(webhook("PAYMENT_REFUNDED", chargeId).statusCode()).isEqualTo(200);

        await("the Order to be refunded", () -> "REFUNDED".equals(status(orderId)));
        assertThat(jdbc.queryForObject(
                        "select cancelled_at is not null from accesses where order_id = ?", Boolean.class, orderId))
                .isTrue();
    }

    @Test
    void shouldOnlyExistWithTheFakeProvider() {
        ConditionalOnProperty condition = LocalDemoRefundController.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly("app.pix.provider");
        assertThat(condition.havingValue()).isEqualTo("fake");
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }
}
