package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.json;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.priceJson;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.updateJson;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.validBody;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.validUpdate;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.audit.SwitchableAuditLog;
import br.com.sergio.sapiensia.audit.SwitchableAuditLogConfig;
import br.com.sergio.sapiensia.security.SecretCipher;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * SEC-065: the audit trail of Product actions. audit_events is append-only, so every check filters by the correlation
 * id of its own request.
 */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class, SwitchableAuditLogConfig.class})
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-product-audit", "app.login-limit.ip-max-attempts=1000"})
class AdminProductAuditTest {

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
    SwitchableAuditLog auditLog;

    private AdminBrowser admin;
    private UUID adminId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from products");
        AdminProductFixtures.Admin account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        adminId = account.id();
        admin = new AdminBrowser(port);
        assertThat(admin.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
    }

    private static String correlationId(HttpResponse<String> response) {
        return response.headers().firstValue("X-Correlation-Id").orElseThrow();
    }

    private List<Map<String, Object>> rowsOf(String correlationId) {
        return jdbc.queryForList(
                "select action, result, actor_id, object_type, object_id, host(origin) as origin"
                        + " from audit_events where correlation_id = ?::uuid",
                correlationId);
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from products", Integer.class);
    }

    @Test
    @DisplayName("SEC-065: criar grava ADMIN_PRODUCT_CREATED SUCCESS com ator, objeto, origem e correlation ID")
    void shouldAuditTheCreation() throws Exception {
        HttpResponse<String> response = admin.post(PRODUCTS, validBody());

        assertThat(response.statusCode()).isEqualTo(201);
        List<Map<String, Object>> rows = rowsOf(correlationId(response));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("action", "ADMIN_PRODUCT_CREATED")
                .containsEntry("result", "SUCCESS")
                .containsEntry("actor_id", adminId)
                .containsEntry("object_type", "PRODUCT")
                .containsEntry("object_id", UUID.fromString(JsonPath.read(response.body(), "$.id")))
                .hasEntrySatisfying("origin", origin -> assertThat(origin).isIn("127.0.0.1", "::1"));
    }

    private void assertOneEvent(HttpResponse<String> response, String action, String result, UUID productId) {
        List<Map<String, Object>> rows = rowsOf(correlationId(response));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("action", action)
                .containsEntry("result", result)
                .containsEntry("actor_id", adminId)
                .containsEntry("object_type", "PRODUCT")
                .containsEntry("object_id", productId)
                .hasEntrySatisfying("origin", origin -> assertThat(origin).isIn("127.0.0.1", "::1"));
    }

    @Test
    @DisplayName("SEC-065: editar grava ADMIN_PRODUCT_UPDATED SUCCESS com ator, objeto, origem e correlation ID")
    void shouldAuditTheEdit() throws Exception {
        UUID id = AdminProductFixtures.insertProduct(jdbc);

        HttpResponse<String> response = admin.put(PRODUCTS + "/" + id, validUpdate(0));

        assertThat(response.statusCode()).isEqualTo(200);
        assertOneEvent(response, "ADMIN_PRODUCT_UPDATED", "SUCCESS", id);
    }

    @Test
    @DisplayName("SEC-065 SEC-029: trocar o preço grava ADMIN_PRODUCT_PRICE_CHANGED SUCCESS")
    void shouldAuditThePriceChange() throws Exception {
        UUID id = AdminProductFixtures.insertProduct(jdbc);

        HttpResponse<String> response = admin.put(PRODUCTS + "/" + id + "/price", priceJson(7777, 0));

        assertThat(response.statusCode()).isEqualTo(200);
        assertOneEvent(response, "ADMIN_PRODUCT_PRICE_CHANGED", "SUCCESS", id);
    }

    @Test
    @DisplayName("SEC-065: versão velha (409) grava FAILURE nas duas rotas, e a linha do Produto não muda")
    void shouldAuditAStaleVersionAsFailure() throws Exception {
        UUID id = AdminProductFixtures.insertProduct(jdbc);
        jdbc.update("update products set version = 3 where id = ?", id);

        HttpResponse<String> text = admin.put(PRODUCTS + "/" + id, validUpdate(0));
        HttpResponse<String> price = admin.put(PRODUCTS + "/" + id + "/price", priceJson(7777, 0));

        assertThat(text.statusCode()).isEqualTo(409);
        assertThat(price.statusCode()).isEqualTo(409);
        assertOneEvent(text, "ADMIN_PRODUCT_UPDATED", "FAILURE", id);
        assertOneEvent(price, "ADMIN_PRODUCT_PRICE_CHANGED", "FAILURE", id);
        assertThat(jdbc.queryForMap("select title, price_cents, version from products where id = ?", id))
                .containsEntry("title", "Original")
                .containsEntry("price_cents", 4990)
                .containsEntry("version", 3);
    }

    @Test
    @DisplayName("SEC-065: PUT com 400 ou 404 não grava nada na trilha")
    void shouldNotAuditRefusedPuts() throws Exception {
        UUID id = AdminProductFixtures.insertProduct(jdbc);
        List<HttpResponse<String>> answers = List.of(
                admin.put(PRODUCTS + "/" + id, updateJson("", "D", "LAW", 0)),
                admin.put(PRODUCTS + "/" + id + "/price", priceJson(5, 0)),
                admin.put(PRODUCTS + "/" + UUID.randomUUID(), validUpdate(0)),
                admin.put(PRODUCTS + "/" + UUID.randomUUID() + "/price", priceJson(5000, 0)));

        assertThat(answers).extracting(HttpResponse::statusCode).containsExactly(400, 400, 404, 404);
        for (HttpResponse<String> answer : answers) {
            assertThat(rowsOf(correlationId(answer))).isEmpty();
        }
    }

    @Test
    @DisplayName("SEC-065: sem a trilha editar e trocar o preço dão 503 e nada muda")
    void shouldNotEditWhenTheAuditFails() throws Exception {
        UUID id = AdminProductFixtures.insertProduct(jdbc);
        jdbc.execute("create function test_product_audit_down() returns trigger language plpgsql as $$"
                + " begin raise exception 'audit down'; end $$");
        jdbc.execute("create trigger test_product_audit_down before insert on audit_events for each row"
                + " when (new.result = 'SUCCESS' and new.action in"
                + " ('ADMIN_PRODUCT_UPDATED', 'ADMIN_PRODUCT_PRICE_CHANGED'))"
                + " execute function test_product_audit_down()");
        HttpResponse<String> text;
        HttpResponse<String> price;
        try {
            text = admin.put(PRODUCTS + "/" + id, validUpdate(0));
            price = admin.put(PRODUCTS + "/" + id + "/price", priceJson(7777, 0));
        } finally {
            jdbc.execute("drop trigger test_product_audit_down on audit_events");
            jdbc.execute("drop function test_product_audit_down()");
        }

        assertThat(text.statusCode()).isEqualTo(503);
        assertThat(text.body()).isEmpty();
        assertThat(price.statusCode()).isEqualTo(503);
        assertThat(price.body()).isEmpty();
        assertThat(rowsOf(correlationId(text))).isEmpty();
        assertThat(rowsOf(correlationId(price))).isEmpty();
        assertThat(jdbc.queryForMap("select title, price_cents, version from products where id = ?", id))
                .containsEntry("title", "Original")
                .containsEntry("price_cents", 4990)
                .containsEntry("version", 0);
    }

    @Test
    @DisplayName("SEC-018 SEC-065: se o gravador da trilha falha, criar, editar e trocar o preço dão 503 sem corpo")
    void shouldAnswer503WhenTheAuditWriterFails() throws Exception {
        UUID id = AdminProductFixtures.insertProduct(jdbc);
        auditLog.failWrites(true);
        HttpResponse<String> create;
        HttpResponse<String> text;
        HttpResponse<String> price;
        try {
            create = admin.post(PRODUCTS, validBody());
            text = admin.put(PRODUCTS + "/" + id, validUpdate(0));
            price = admin.put(PRODUCTS + "/" + id + "/price", priceJson(7777, 0));
        } finally {
            auditLog.failWrites(false);
        }

        assertThat(List.of(create, text, price)).allSatisfy(answer -> {
            assertThat(answer.statusCode()).isEqualTo(503);
            assertThat(answer.body()).isEmpty();
        });
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForMap("select title, price_cents, version from products where id = ?", id))
                .containsEntry("title", "Original")
                .containsEntry("price_cents", 4990)
                .containsEntry("version", 0);
    }

    @Test
    @DisplayName("SEC-065: 400 e 404 e as leituras não gravam nada na trilha")
    void shouldNotAuditRefusalsAndReads() throws Exception {
        List<HttpResponse<String>> answers = List.of(
                admin.post(PRODUCTS, json("", "D", "LAW", 500)),
                admin.post(PRODUCTS, json("T", "D", "LAW", 5)),
                admin.get(PRODUCTS + "/" + UUID.randomUUID()),
                admin.get(PRODUCTS + "/not-a-uuid"),
                admin.get(PRODUCTS),
                admin.post(PRODUCTS, "{not json"));

        assertThat(answers).extracting(HttpResponse::statusCode).containsExactly(400, 400, 404, 400, 200, 400);
        for (HttpResponse<String> answer : answers) {
            assertThat(rowsOf(correlationId(answer))).isEmpty();
        }
    }

    @Test
    @DisplayName("SEC-065: sem a trilha criar dá 503 e nenhum Produto é gravado; título e preço não vão ao log")
    void shouldNotCreateWhenTheAuditFails(CapturedOutput output) throws Exception {
        String title = "Titulo-secreto-" + UUID.randomUUID();
        jdbc.execute("create function test_product_audit_down() returns trigger language plpgsql as $$"
                + " begin raise exception 'audit down'; end $$");
        jdbc.execute("create trigger test_product_audit_down before insert on audit_events for each row"
                + " when (new.result = 'SUCCESS' and new.action = 'ADMIN_PRODUCT_CREATED')"
                + " execute function test_product_audit_down()");
        HttpResponse<String> down;
        try {
            down = admin.post(PRODUCTS, json(title, "D", "LAW", 123_456));
        } finally {
            jdbc.execute("drop trigger test_product_audit_down on audit_events");
            jdbc.execute("drop function test_product_audit_down()");
        }

        assertThat(down.statusCode()).isEqualTo(503);
        assertThat(down.body()).isEmpty();
        assertThat(count()).isZero();
        assertThat(rowsOf(correlationId(down))).isEmpty();
        assertThat(output.getAll()).doesNotContain(title).doesNotContain("123456");
        // once the trail is back the same request works
        assertThat(admin.post(PRODUCTS, json(title, "D", "LAW", 123_456)).statusCode())
                .isEqualTo(201);
    }
}
