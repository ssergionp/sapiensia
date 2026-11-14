package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** FR-025, FR-029, SEC-011: GET /api/library lists the active Accesses of the logged-in Customer. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=library", "app.login-limit.ip-max-attempts=1000"})
class LibraryTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private OrderHttp.Customer customer;
    private OrderHttp.Customer other;
    private UUID first;
    private UUID second;

    @BeforeEach
    void setUp() throws Exception {
        customer = OrderHttp.newCustomer(port, jdbc, encoder);
        other = OrderHttp.newCustomer(port, jdbc, encoder);
        first = OrderTestSupport.insertPublishedProduct(jdbc, "Guia de Contratos", 2990);
        second = OrderTestSupport.insertPublishedProduct(jdbc, "Checklist de Locacao", 1990);
    }

    @AfterEach
    void cleanUp() {
        OrderTestSupport.deleteOrdersOf(jdbc, customer.id, first, second);
        OrderTestSupport.deleteOrdersOf(jdbc, other.id, first, second);
        jdbc.update("delete from product_covers where product_id in (?, ?)", first, second);
    }

    private void grant(OrderHttp.Customer buyer, UUID productId, String grantedAt) {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at, paid_at)"
                        + " values (?, ?, ?, 1000, 'PAID', ?, now(), now() + interval '30 minutes', now())",
                orderId,
                buyer.id,
                productId,
                "pay_" + UUID.randomUUID());
        jdbc.update(
                "insert into accesses (customer_id, product_id, order_id, granted_at)"
                        + " values (?, ?, ?, now() - cast(? as interval))",
                buyer.id,
                productId,
                orderId,
                grantedAt);
    }

    private void addCover(UUID productId, String version) {
        jdbc.update(
                "insert into product_covers (product_id, object_key, media_type, size_bytes, width, height, version)"
                        + " values (?, ?, 'image/png', 100, 10, 10, ?)",
                productId,
                "covers/" + productId + "/" + UUID.randomUUID() + ".png",
                version);
    }

    private HttpResponse<String> library(OrderHttp.Customer who) throws Exception {
        return who.get("/api/library");
    }

    @Test
    @DisplayName("FR-029: shouldListTheProductsTheCustomerBought")
    void shouldListTheProductsTheCustomerBought() throws Exception {
        grant(customer, first, "2 days");
        grant(customer, second, "1 day");
        addCover(first, "abcdef0123456789");

        HttpResponse<String> answer = library(customer);

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(OrderHttp.json(answer, "$.items.length()")).isEqualTo("2");
        assertThat(OrderHttp.json(answer, "$.items[0].productId")).isEqualTo(second.toString());
        assertThat(OrderHttp.json(answer, "$.items[0].title")).isEqualTo("Checklist de Locacao");
        assertThat(OrderHttp.json(answer, "$.items[0].coverVersion")).isNull();
        assertThat(OrderHttp.json(answer, "$.items[0].grantedAt")).isNotBlank();
        assertThat(OrderHttp.json(answer, "$.items[1].productId")).isEqualTo(first.toString());
        assertThat(OrderHttp.json(answer, "$.items[1].title")).isEqualTo("Guia de Contratos");
        assertThat(OrderHttp.json(answer, "$.items[1].coverVersion")).isEqualTo("abcdef0123456789");
    }

    @Test
    @DisplayName("FR-029: shouldKeepAnUnpublishedProductInTheLibrary")
    void shouldKeepAnUnpublishedProductInTheLibrary() throws Exception {
        grant(customer, first, "1 day");
        jdbc.update("update products set status = 'UNPUBLISHED' where id = ?", first);

        HttpResponse<String> answer = library(customer);

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(OrderHttp.json(answer, "$.items.length()")).isEqualTo("1");
        assertThat(OrderHttp.json(answer, "$.items[0].productId")).isEqualTo(first.toString());
    }

    @Test
    @DisplayName("FR-025: shouldHideACancelledAccess")
    void shouldHideACancelledAccess() throws Exception {
        grant(customer, first, "2 days");
        grant(customer, second, "1 day");
        jdbc.update(
                "update accesses set cancelled_at = now() where customer_id = ? and product_id = ?",
                customer.id,
                first);

        HttpResponse<String> answer = library(customer);

        assertThat(OrderHttp.json(answer, "$.items.length()")).isEqualTo("1");
        assertThat(OrderHttp.json(answer, "$.items[0].productId")).isEqualTo(second.toString());
        assertThat(answer.body()).doesNotContain(first.toString());
    }

    @Test
    @DisplayName("FR-029: shouldAnswerAnEmptyListForACustomerWithoutAccesses")
    void shouldAnswerAnEmptyListForACustomerWithoutAccesses() throws Exception {
        HttpResponse<String> answer = library(customer);

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(OrderHttp.json(answer, "$.items.length()")).isEqualTo("0");
    }

    @Test
    @DisplayName("FR-029, SEC-011: shouldNeverListAnotherCustomersAccesses")
    void shouldNeverListAnotherCustomersAccesses() throws Exception {
        grant(customer, first, "2 days");
        grant(other, second, "1 day");

        HttpResponse<String> mine = library(customer);
        HttpResponse<String> theirs = library(other);

        assertThat(OrderHttp.json(mine, "$.items.length()")).isEqualTo("1");
        assertThat(OrderHttp.json(mine, "$.items[0].productId")).isEqualTo(first.toString());
        assertThat(mine.body()).doesNotContain(second.toString());
        assertThat(OrderHttp.json(theirs, "$.items.length()")).isEqualTo("1");
        assertThat(OrderHttp.json(theirs, "$.items[0].productId")).isEqualTo(second.toString());
        assertThat(theirs.body()).doesNotContain(first.toString());
    }

    @Test
    @DisplayName("FR-029: shouldNotExposeOrderPriceOrProductStatus")
    void shouldNotExposeOrderPriceOrProductStatus() throws Exception {
        grant(customer, first, "1 day");

        HttpResponse<String> answer = library(customer);

        Map<String, Object> item = JsonPath.read(answer.body(), "$.items[0]");
        assertThat(item.keySet()).containsExactlyInAnyOrder("productId", "title", "grantedAt", "coverVersion");
        assertThat(JsonPath.<Map<String, Object>>read(answer.body(), "$").keySet())
                .isEqualTo(java.util.Set.of("items"));
    }

    @Test
    @DisplayName("SEC-010: shouldRefuseAnonymousWith401")
    void shouldRefuseAnonymousWith401() throws Exception {
        grant(customer, first, "1 day");

        HttpResponse<String> answer = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/library"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(answer.statusCode()).isEqualTo(401);
        assertThat(answer.body()).doesNotContain(first.toString());
        assertThat(List.of(customer.send("POST", "/api/library", "{}").statusCode()))
                .allMatch(code -> code == 403 || code == 405);
    }
}
