package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.ORDERS;
import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.insertCustomer;
import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.insertOrder;
import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.insertProduct;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.payment.SwitchablePixGateway;
import br.com.sergio.sapiensia.payment.SwitchablePixGatewayConfig;
import br.com.sergio.sapiensia.security.SecretCipher;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** FR-020, SEC-010, SEC-015: the Administrator's list of Orders. */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-orders", "app.login-limit.ip-max-attempts=1000"})
class AdminOrderListTest {

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

    @Autowired
    SwitchablePixGateway gateway;

    private AdminBrowser admin;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from orders");
        jdbc.update("delete from customers where email in (?, ?)", "maria.silva@example.com", "joao.souza@example.org");
        AdminProductFixtures.Admin account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        admin = new AdminBrowser(port);
        assertThat(admin.loginAdminFully(account.email(), AdminProductFixtures.PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
    }

    private HttpResponse<String> list(String query) throws Exception {
        return admin.get(ORDERS + query);
    }

    private static String json(HttpResponse<String> answer, String path) {
        Object value = JsonPath.read(answer.body(), path);
        return String.valueOf(value);
    }

    @Test
    @DisplayName(
            "FR-020 SEC-010: o Administrador vê os Pedidos do mais recente para o mais antigo, só com os campos permitidos")
    void shouldListNewestFirstWithOnlyTheAllowedFields() throws Exception {
        Instant now = clock.instant();
        UUID maria = insertCustomer(jdbc, passwordEncoder, "maria.silva@example.com");
        UUID joao = insertCustomer(jdbc, passwordEncoder, "joao.souza@example.org");
        UUID paidProduct = insertProduct(jdbc, "Guia Pago");
        UUID expiredProduct = insertProduct(jdbc, "Guia Expirado");
        UUID pendingProduct = insertProduct(jdbc, "Guia Pendente");
        UUID paid = insertOrder(
                jdbc, maria, paidProduct, 2990, "PAID", now.minus(Duration.ofHours(5)), now.minus(Duration.ofHours(4)));
        UUID expired = insertOrder(
                jdbc,
                joao,
                expiredProduct,
                1990,
                "EXPIRED",
                now.minus(Duration.ofHours(3)),
                now.minus(Duration.ofHours(2)));
        UUID pending = insertOrder(
                jdbc,
                maria,
                pendingProduct,
                4990,
                "PENDING",
                now.minus(Duration.ofMinutes(5)),
                now.plus(Duration.ofMinutes(25)));

        HttpResponse<String> answer = list("");

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(json(answer, "$.page")).isEqualTo("0");
        assertThat(json(answer, "$.totalPages")).isEqualTo("1");
        assertThat(json(answer, "$.items.length()")).isEqualTo("3");
        assertThat(json(answer, "$.items[0].id")).isEqualTo(pending.toString());
        assertThat(json(answer, "$.items[0].productTitle")).isEqualTo("Guia Pendente");
        assertThat(json(answer, "$.items[0].priceCents")).isEqualTo("4990");
        assertThat(json(answer, "$.items[0].status")).isEqualTo("PENDING");
        assertThat(json(answer, "$.items[0].customerEmail")).isEqualTo("m***@example.com");
        assertThat(json(answer, "$.items[1].id")).isEqualTo(expired.toString());
        assertThat(json(answer, "$.items[1].status")).isEqualTo("EXPIRED");
        assertThat(json(answer, "$.items[1].customerEmail")).isEqualTo("j***@example.org");
        assertThat(json(answer, "$.items[2].id")).isEqualTo(paid.toString());
        assertThat(json(answer, "$.items[2].status")).isEqualTo("PAID");
        assertThat(json(answer, "$.items[2].priceCents")).isEqualTo("2990");
        assertThat(json(answer, "$.items[2].paidAt")).isNotBlank().isNotEqualTo("null");
        assertThat(json(answer, "$.items[0].paidAt")).isEqualTo("null");
        assertThat(json(answer, "$.items[1].paidAt")).isEqualTo("null");
        for (int i = 0; i < 3; i++) {
            Map<String, Object> item = JsonPath.read(answer.body(), "$.items[" + i + "]");
            assertThat(item.keySet())
                    .containsExactlyInAnyOrder(
                            "id", "productTitle", "priceCents", "status", "customerEmail", "createdAt", "paidAt");
        }
        String body = answer.body();
        assertThat(body).doesNotContain("pay_");
        assertThat(body).doesNotContain("maria.silva@example.com").doesNotContain("joao.souza@example.org");
        for (UUID hidden : List.of(maria, joao, paidProduct, expiredProduct, pendingProduct)) {
            assertThat(body).doesNotContain(hidden.toString());
        }
    }

    @Test
    @DisplayName("FR-020: o e-mail do Cliente sai mascarado")
    void shouldMaskTheCustomerEmail() throws Exception {
        UUID customer = insertCustomer(jdbc, passwordEncoder, "maria.silva@example.com");
        UUID product = insertProduct(jdbc, "Guia");
        Instant now = clock.instant();
        insertOrder(jdbc, customer, product, 1000, "EXPIRED", now.minusSeconds(600), now.minusSeconds(300));

        HttpResponse<String> answer = list("");

        assertThat(json(answer, "$.items[0].customerEmail")).isEqualTo("m***@example.com");
        assertThat(answer.body()).doesNotContain("maria.silva");
    }

    @Test
    @DisplayName("FR-020 SEC-015: 20 por página, sem repetir nem pular com a mesma data de criação")
    void shouldPageByTwentyWithoutRepeatingOrSkipping() throws Exception {
        UUID customer = insertCustomer(jdbc, passwordEncoder, "paginas@example.com");
        UUID product = insertProduct(jdbc, "Guia");
        Instant created = clock.instant().minus(Duration.ofHours(2));
        for (int i = 0; i < 45; i++) {
            insertOrder(jdbc, customer, product, 1000, "EXPIRED", created, created.plusSeconds(1800));
        }

        HttpResponse<String> first = list("?page=0");
        HttpResponse<String> second = list("?page=1");
        HttpResponse<String> third = list("?page=2");
        HttpResponse<String> beyond = list("?page=3");

        assertThat(json(first, "$.items.length()")).isEqualTo("20");
        assertThat(json(second, "$.items.length()")).isEqualTo("20");
        assertThat(json(third, "$.items.length()")).isEqualTo("5");
        assertThat(json(first, "$.totalPages")).isEqualTo("3");
        assertThat(json(third, "$.page")).isEqualTo("2");
        Set<String> ids = new HashSet<>();
        for (HttpResponse<String> page : List.of(first, second, third)) {
            List<String> pageIds = JsonPath.read(page.body(), "$.items[*].id");
            ids.addAll(pageIds);
        }
        assertThat(ids).hasSize(45);
        assertThat(beyond.statusCode()).isEqualTo(200);
        assertThat(json(beyond, "$.items.length()")).isEqualTo("0");
        assertThat(list("?page=-1").statusCode()).isEqualTo(400);
        assertThat(list("?page=abc").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("FR-020: Pedido pendente vencido aparece como expirado, nada é gravado e o Asaas não é chamado")
    void shouldShowAnOverduePendingOrderAsExpiredWithoutWritingOrCallingTheProvider() throws Exception {
        UUID customer = insertCustomer(jdbc, passwordEncoder, "vencido@example.com");
        UUID product = insertProduct(jdbc, "Guia");
        Instant now = clock.instant();
        UUID id = insertOrder(jdbc, customer, product, 1000, "PENDING", now.minusSeconds(3600), now.minusSeconds(1800));
        int before = gateway.calls().size();

        HttpResponse<String> answer = list("");

        assertThat(json(answer, "$.items[0].status")).isEqualTo("EXPIRED");
        Map<String, Object> row = jdbc.queryForMap("select status, expired_at from orders where id = ?", id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("expired_at")).isNull();
        assertThat(gateway.calls()).hasSize(before);
    }

    @Test
    @DisplayName("FR-020: o filtro usa o estado mostrado")
    void shouldFilterByTheShownStatus() throws Exception {
        UUID customer = insertCustomer(jdbc, passwordEncoder, "filtro@example.com");
        Instant now = clock.instant();
        insertOrder(
                jdbc, customer, insertProduct(jdbc, "A"), 1000, "PENDING", now.minusSeconds(60), now.plusSeconds(600));
        insertOrder(
                jdbc,
                customer,
                insertProduct(jdbc, "B"),
                1000,
                "PENDING",
                now.minusSeconds(3600),
                now.minusSeconds(1800));
        insertOrder(
                jdbc,
                customer,
                insertProduct(jdbc, "C"),
                1000,
                "EXPIRED",
                now.minusSeconds(7200),
                now.minusSeconds(5400));
        insertOrder(
                jdbc, customer, insertProduct(jdbc, "D"), 1000, "PAID", now.minusSeconds(900), now.plusSeconds(900));

        HttpResponse<String> pending = list("?status=PENDING");
        HttpResponse<String> expired = list("?status=EXPIRED");
        HttpResponse<String> paid = list("?status=PAID");

        assertThat(json(pending, "$.items.length()")).isEqualTo("1");
        assertThat(json(pending, "$.items[0].productTitle")).isEqualTo("A");
        assertThat(json(expired, "$.items.length()")).isEqualTo("2");
        assertThat(json(paid, "$.items.length()")).isEqualTo("1");
        assertThat(json(paid, "$.totalPages")).isEqualTo("1");
        assertThat(list("?status=REFUNDED").statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("FR-020: o Pedido de um Produto despublicado continua na lista")
    void shouldKeepTheOrderOfAnUnpublishedProduct() throws Exception {
        UUID customer = insertCustomer(jdbc, passwordEncoder, "despublicado@example.com");
        UUID product = insertProduct(jdbc, "Guia Despublicado");
        Instant now = clock.instant();
        insertOrder(jdbc, customer, product, 1000, "PAID", now.minusSeconds(600), now.plusSeconds(600));
        jdbc.update("update products set status = 'UNPUBLISHED' where id = ?", product);

        HttpResponse<String> answer = list("");

        assertThat(json(answer, "$.items.length()")).isEqualTo("1");
        assertThat(json(answer, "$.items[0].productTitle")).isEqualTo("Guia Despublicado");
    }

    @Test
    @DisplayName("FR-020 SEC-015: filtro, estado mostrado e contagem usam o mesmo agora")
    void shouldUseTheSameNowForFilterShownStatusAndCount() throws Exception {
        UUID customer = insertCustomer(jdbc, passwordEncoder, "limite@example.com");
        // the clock only moves when the test moves it, so the request reads exactly this instant
        Instant now = clock.instant();
        Instant created = now.minus(Duration.ofHours(2));
        for (int i = 0; i < 20; i++) {
            insertOrder(
                    jdbc,
                    customer,
                    insertProduct(jdbc, "Expirado " + i),
                    1000,
                    "EXPIRED",
                    created,
                    created.plusSeconds(1800));
        }
        // the oldest one, so it is the only row of page 1; it expires exactly at the instant the request reads
        UUID boundary = insertOrder(
                jdbc,
                customer,
                insertProduct(jdbc, "No limite"),
                1000,
                "PENDING",
                created.minus(Duration.ofHours(1)),
                now);

        HttpResponse<String> expired0 = list("?status=EXPIRED");
        HttpResponse<String> expired1 = list("?status=EXPIRED&page=1");
        HttpResponse<String> pending = list("?status=PENDING");
        HttpResponse<String> all0 = list("");
        HttpResponse<String> all1 = list("?page=1");

        assertThat(json(expired0, "$.totalPages")).isEqualTo("2");
        assertThat(json(expired1, "$.items.length()")).isEqualTo("1");
        assertThat(json(expired1, "$.items[0].id")).isEqualTo(boundary.toString());
        assertThat(json(expired1, "$.items[0].status")).isEqualTo("EXPIRED");
        assertThat(json(pending, "$.items.length()")).isEqualTo("0");
        assertThat(json(pending, "$.totalPages")).isEqualTo("0");
        assertThat(json(all0, "$.totalPages")).isEqualTo("2");
        assertThat(json(all0, "$.items.length()")).isEqualTo("20");
        assertThat(json(all1, "$.items.length()")).isEqualTo("1");
        assertThat(all0.body()).doesNotContain("\"PENDING\"");
        assertThat(all1.body()).doesNotContain("\"PENDING\"");
    }
}
