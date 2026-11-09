package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.json;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.validBody;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
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
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
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
