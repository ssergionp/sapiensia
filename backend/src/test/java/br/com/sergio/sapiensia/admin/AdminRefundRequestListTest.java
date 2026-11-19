package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.insertCustomer;
import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.insertOrder;
import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.insertProduct;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.payment.SwitchablePixGatewayConfig;
import br.com.sergio.sapiensia.security.SecretCipher;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** FR-023, SEC-036: the Administrator's list of Refund requests. */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-orders", "app.login-limit.ip-max-attempts=1000"})
class AdminRefundRequestListTest {

    private static final String REFUNDS = "/api/admin/refund-requests";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AdminMfaTestSupport.MutableClock clock;

    private AdminBrowser admin;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from refund_requests");
        jdbc.update("delete from orders");
        AdminProductFixtures.Admin account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        admin = new AdminBrowser(port);
        assertThat(admin.loginAdminFully(account.email(), AdminProductFixtures.PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
    }

    /** Siblings of this context run "delete from orders", which a leftover request would block. */
    @AfterEach
    void deleteRequests() {
        jdbc.update("delete from refund_requests");
    }

    private HttpResponse<String> list(String query) throws Exception {
        return admin.get(REFUNDS + query);
    }

    private static String json(HttpResponse<String> answer, String path) {
        Object value = JsonPath.read(answer.body(), path);
        return String.valueOf(value);
    }

    /** A Customer with a fresh e-mail: download_records pins the Customer, so these are never deleted. */
    private UUID newCustomer(String name) {
        return insertCustomer(jdbc, passwordEncoder, name + "-" + UUID.randomUUID() + "@example.com");
    }

    /** A PAID Order created two hours ago. */
    private UUID paidOrder(UUID customer, UUID product, int priceCents) {
        Instant created = clock.instant().minus(2, ChronoUnit.HOURS);
        return insertOrder(jdbc, customer, product, priceCents, "PAID", created, created.plusSeconds(1800));
    }

    private void request(UUID order, UUID customer, String reason, String details, Instant requestedAt) {
        jdbc.update(
                "insert into refund_requests (id, order_id, customer_id, reason, details, requested_at)"
                        + " values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                order,
                customer,
                reason,
                details,
                Timestamp.from(requestedAt));
    }

    private void refund(UUID order, Instant refundedAt) {
        jdbc.update(
                "update orders set status = 'REFUNDED', refunded_at = ? where id = ?",
                Timestamp.from(refundedAt),
                order);
    }

    private void download(UUID customer, UUID product) {
        jdbc.update(
                "insert into download_records (customer_id, product_id, file_uploaded_at, downloaded_at)"
                        + " values (?, ?, now(), now())",
                customer,
                product);
    }

    @Test
    @DisplayName("FR-023: a lista traz motivo, comentário, Downloads do Produto, outros Reembolsos e a cobrança")
    void shouldListReasonCommentDownloadsOtherRefundsAndTheCharge() throws Exception {
        Instant now = clock.instant();
        UUID customer = newCustomer("maria");
        UUID product = insertProduct(jdbc, "Guia do Pedido");
        UUID order = paidOrder(customer, product, 2990);
        request(order, customer, "NOT_AS_EXPECTED", "Faltou um capítulo", now);
        download(customer, product);
        download(customer, product);
        // another Product of the same Customer: its Download does not count
        download(customer, insertProduct(jdbc, "Outro Download"));
        // other Orders: refunded inside the 12 months (counts), refunded long before (does not), no refund (does not)
        UUID refundedRecently = paidOrder(customer, insertProduct(jdbc, "Outro Guia"), 1000);
        refund(refundedRecently, now.minus(30, ChronoUnit.DAYS));
        UUID refundedLongAgo = paidOrder(customer, insertProduct(jdbc, "Guia Antigo"), 1000);
        refund(refundedLongAgo, now.minus(400, ChronoUnit.DAYS));
        paidOrder(customer, insertProduct(jdbc, "Guia Sem Pedido"), 1000);
        String chargeId =
                jdbc.queryForObject("select provider_payment_id from orders where id = ?", String.class, order);

        HttpResponse<String> answer = list("");

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(json(answer, "$.page")).isEqualTo("0");
        assertThat(json(answer, "$.totalPages")).isEqualTo("1");
        assertThat(json(answer, "$.items.length()")).isEqualTo("1");
        assertThat(json(answer, "$.items[0].orderId")).isEqualTo(order.toString());
        assertThat(json(answer, "$.items[0].productTitle")).isEqualTo("Guia do Pedido");
        assertThat(json(answer, "$.items[0].priceCents")).isEqualTo("2990");
        assertThat(json(answer, "$.items[0].reason")).isEqualTo("NOT_AS_EXPECTED");
        assertThat(json(answer, "$.items[0].details")).isEqualTo("Faltou um capítulo");
        assertThat(json(answer, "$.items[0].downloads")).isEqualTo("2");
        assertThat(json(answer, "$.items[0].otherRefunds")).isEqualTo("1");
        assertThat(json(answer, "$.items[0].chargeId")).isEqualTo(chargeId);
        assertThat(json(answer, "$.items[0].requestedAt")).isNotBlank().isNotEqualTo("null");
        assertThat(json(answer, "$.items[0].paidAt")).isNotBlank().isNotEqualTo("null");
        assertThat(json(answer, "$.items[0].refundedAt")).isEqualTo("null");
        Map<String, Object> item = JsonPath.read(answer.body(), "$.items[0]");
        assertThat(item.keySet())
                .containsExactlyInAnyOrder(
                        "orderId",
                        "productTitle",
                        "priceCents",
                        "customerEmail",
                        "reason",
                        "details",
                        "requestedAt",
                        "paidAt",
                        "downloads",
                        "otherRefunds",
                        "chargeId",
                        "refundedAt");
    }

    @Test
    @DisplayName("FR-023: outros Reembolsos conta cada outro Pedido uma vez, pedido e reembolsado")
    void shouldCountEachOtherOrderOnce() throws Exception {
        Instant now = clock.instant();
        UUID customer = newCustomer("carla");
        UUID order = paidOrder(customer, insertProduct(jdbc, "Guia A"), 1000);
        request(order, customer, "OTHER", null, now);
        UUID requestedAndRefunded = paidOrder(customer, insertProduct(jdbc, "Guia B"), 1000);
        request(requestedAndRefunded, customer, "OTHER", null, now.minus(10, ChronoUnit.DAYS));
        refund(requestedAndRefunded, now.minus(5, ChronoUnit.DAYS));
        UUID requestedOnly = paidOrder(customer, insertProduct(jdbc, "Guia C"), 1000);
        request(requestedOnly, customer, "OTHER", null, now.minus(20, ChronoUnit.DAYS));

        HttpResponse<String> answer = list("?status=OPEN");

        assertThat(json(answer, "$.items[0].orderId")).isEqualTo(order.toString());
        assertThat(json(answer, "$.items[0].otherRefunds")).isEqualTo("2");
        assertThat(json(answer, "$.items[0].details")).isEqualTo("null");
        assertThat(json(answer, "$.items[0].downloads")).isEqualTo("0");
        // the oldest request sees only the two newer ones as "others" if they are inside ITS own 12 months
        assertThat(json(answer, "$.items[1].orderId")).isEqualTo(requestedOnly.toString());
        assertThat(json(answer, "$.items[1].otherRefunds")).isEqualTo("2");
    }

    @Test
    @DisplayName("SEC-036: o e-mail vem mascarado e o inteiro não aparece no corpo")
    void shouldMaskTheEmailAndNeverReturnTheFullOne() throws Exception {
        String email = "maria-" + UUID.randomUUID() + "@example.com";
        UUID customer = insertCustomer(jdbc, passwordEncoder, email);
        UUID order = paidOrder(customer, insertProduct(jdbc, "Guia"), 1000);
        request(order, customer, "BOUGHT_BY_MISTAKE", "ok", clock.instant());

        HttpResponse<String> answer = list("");

        assertThat(json(answer, "$.items[0].customerEmail")).isEqualTo("m***@example.com");
        assertThat(answer.body()).doesNotContain(email).doesNotContain("maria-");
        assertThat(answer.body()).doesNotContain(customer.toString());
    }

    @Test
    @DisplayName("FR-023: o filtro OPEN traz o Pedido pago e REFUNDED o reembolsado; sem filtro vêm os dois")
    void shouldFilterOpenAndRefunded() throws Exception {
        Instant now = clock.instant();
        UUID customer = newCustomer("filtro");
        UUID open = paidOrder(customer, insertProduct(jdbc, "Aberto"), 1000);
        request(open, customer, "OTHER", null, now);
        UUID refunded = paidOrder(customer, insertProduct(jdbc, "Reembolsado"), 1000);
        request(refunded, customer, "DEFECTIVE_FILE", null, now.minusSeconds(60));
        refund(refunded, now.minusSeconds(30));
        // a PAID Order without a request is not in the list
        paidOrder(customer, insertProduct(jdbc, "Sem Pedido"), 1000);

        HttpResponse<String> all = list("");
        HttpResponse<String> onlyOpen = list("?status=OPEN");
        HttpResponse<String> onlyRefunded = list("?status=REFUNDED");

        assertThat(json(all, "$.items.length()")).isEqualTo("2");
        assertThat(json(onlyOpen, "$.items.length()")).isEqualTo("1");
        assertThat(json(onlyOpen, "$.items[0].orderId")).isEqualTo(open.toString());
        assertThat(json(onlyOpen, "$.items[0].refundedAt")).isEqualTo("null");
        assertThat(json(onlyRefunded, "$.items.length()")).isEqualTo("1");
        assertThat(json(onlyRefunded, "$.items[0].orderId")).isEqualTo(refunded.toString());
        assertThat(json(onlyRefunded, "$.items[0].refundedAt")).isNotBlank().isNotEqualTo("null");
        assertThat(list("?status=PAID").statusCode()).isEqualTo(400);
        assertThat(list("?status=open").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("FR-023: paginação, 21 solicitações dão 2 páginas, da mais nova para a mais antiga")
    void shouldPageTwentyOneRequestsIntoTwoPages() throws Exception {
        Instant now = clock.instant();
        UUID customer = newCustomer("paginas");
        UUID product = insertProduct(jdbc, "Guia");
        for (int i = 0; i < 21; i++) {
            UUID order = paidOrder(customer, product, 1000);
            request(order, customer, "OTHER", null, now.minusSeconds(i));
        }

        HttpResponse<String> first = list("?page=0");
        HttpResponse<String> second = list("?page=1");

        assertThat(json(first, "$.totalPages")).isEqualTo("2");
        assertThat(json(first, "$.items.length()")).isEqualTo("20");
        assertThat(json(second, "$.items.length()")).isEqualTo("1");
        assertThat(json(second, "$.page")).isEqualTo("1");
        Set<String> ids = new HashSet<>();
        ids.addAll(JsonPath.<List<String>>read(first.body(), "$.items[*].orderId"));
        ids.addAll(JsonPath.<List<String>>read(second.body(), "$.items[*].orderId"));
        assertThat(ids).hasSize(21);
        List<String> times = JsonPath.read(first.body(), "$.items[*].requestedAt");
        assertThat(times).isSortedAccordingTo(Comparator.<String>naturalOrder().reversed());
        assertThat(list("?page=-1").statusCode()).isEqualTo(400);
        assertThat(list("?page=10001").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("SEC-036: comentário com <script> volta como texto, sem alteração")
    void shouldReturnTheCommentAsStoredWithoutEscapingOrStripping() throws Exception {
        UUID customer = newCustomer("script");
        UUID order = paidOrder(customer, insertProduct(jdbc, "Guia"), 1000);
        String comment = "<script>alert('x')</script> & \"aspas\"";
        request(order, customer, "OTHER", comment, clock.instant());

        HttpResponse<String> answer = list("");

        assertThat(json(answer, "$.items[0].details")).isEqualTo(comment);
        assertThat(answer.headers().firstValue("Content-Type").orElse("")).contains("application/json");
    }
}
