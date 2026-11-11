package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
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

/**
 * SEC-010, SEC-011, SEC-017: who may publish or unpublish a Product, by state of the session. A denied answer is the
 * same whether the Product exists or not, and a denied or unknown request changes nothing.
 */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=admin-product-publication-authorization",
            "app.login-limit.ip-max-attempts=1000"
        })
class AdminProductPublicationAuthorizationTest {

    /** One way to call the route: PUT publishes, DELETE unpublishes. */
    private record Verb(String name, BiFunction<AdminBrowser, String, HttpResponse<String>> call) {}

    private static final List<Verb> VERBS = List.of(
            new Verb("PUT", (browser, path) -> send(() -> browser.put(path, ""))),
            new Verb("DELETE", (browser, path) -> send(() -> browser.delete(path))));

    private interface Sender {
        HttpResponse<String> send() throws Exception;
    }

    private static HttpResponse<String> send(Sender sender) {
        try {
            return sender.send();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

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

    private AdminProductFixtures.Admin account;
    private String customerEmail;
    private UUID existing;

    @BeforeEach
    void setUp() {
        clearState();
        account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        customerEmail = "cliente-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, ?, now())",
                customerEmail,
                passwordEncoder.encode(PASSWORD));
        existing = AdminProductFixtures.insertProductWithFile(jdbc);
    }

    @AfterEach
    void tearDown() {
        clearState();
    }

    private void clearState() {
        jdbc.update("delete from product_files");
        jdbc.update("delete from products");
    }

    private static String path(UUID id) {
        return PRODUCTS + "/" + id + "/publication";
    }

    private AdminBrowser fullAdmin() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
        return browser;
    }

    private Map<String, Object> productRow(UUID id) {
        return jdbc.queryForMap("select * from products where id = ?", id);
    }

    private int auditSuccesses() {
        return jdbc.queryForObject(
                "select count(*) from audit_events where result = 'SUCCESS' and action like 'ADMIN_PRODUCT_%PUBLISHED'",
                Integer.class);
    }

    /** The two ids a denied state must answer alike: a Product that exists and one that does not. */
    private void assertDenied(AdminBrowser browser, int status, String state) {
        for (Verb verb : VERBS) {
            HttpResponse<String> forExisting = verb.call().apply(browser, path(existing));
            HttpResponse<String> forMissing = verb.call().apply(browser, path(UUID.randomUUID()));
            String label = state + " " + verb.name();
            assertThat(forExisting.statusCode()).as(label).isEqualTo(status);
            assertThat(forMissing.statusCode()).as(label).isEqualTo(status);
            assertThat(forMissing.body().replaceAll("\"(timestamp|path)\":\"[^\"]*\"", ""))
                    .as(label)
                    .isEqualTo(forExisting.body().replaceAll("\"(timestamp|path)\":\"[^\"]*\"", ""));
            assertThat(forExisting.body().replaceAll("\"path\":\"[^\"]*\"", "")).doesNotContain(existing.toString());
        }
    }

    @Test
    @DisplayName("SEC-010 SEC-011: anônimo recebe 401 e Cliente recebe 403, igual para ID existente e inexistente")
    void shouldDenyAnonymousAndCustomer() throws Exception {
        Map<String, Object> before = productRow(existing);
        assertDenied(new AdminBrowser(port), 401, "anonymous");
        AdminBrowser customer = new AdminBrowser(port);
        assertThat(customer.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);
        assertDenied(customer, 403, "customer");
        assertThat(productRow(existing)).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-010 SEC-001: sessão pendente de MFA (só a senha) e pendente vencida recebem 401")
    void shouldDenyMfaPendingSessions() throws Exception {
        Map<String, Object> before = productRow(existing);
        AdminBrowser pending = new AdminBrowser(port);
        assertThat(pending.loginAdmin(account.email(), PASSWORD).statusCode()).isEqualTo(204);
        assertDenied(pending, 401, "password only");

        clock.advance(Duration.ofMinutes(10));
        assertDenied(pending, 401, "expired pending");
        assertThat(productRow(existing)).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-017: PUT e DELETE sem o token CSRF dão 403 e nada muda")
    void shouldRefuseWithoutCsrfToken() throws Exception {
        AdminBrowser admin = fullAdmin();
        Map<String, Object> before = productRow(existing);

        assertThat(admin.putWithoutCsrf(path(existing), "").statusCode()).isEqualTo(403);
        assertThat(admin.deleteWithoutCsrf(path(existing)).statusCode()).isEqualTo(403);

        assertThat(productRow(existing)).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-011: ID inexistente responde 404 e nada é gravado")
    void shouldAnswer404AndWriteNothingForAnUnknownProduct() throws Exception {
        AdminBrowser admin = fullAdmin();
        Map<String, Object> before = productRow(existing);
        int auditBefore = auditSuccesses();

        for (Verb verb : VERBS) {
            assertThat(verb.call().apply(admin, path(UUID.randomUUID())).statusCode())
                    .as(verb.name())
                    .isEqualTo(404);
        }

        assertThat(productRow(existing)).isEqualTo(before);
        assertThat(auditSuccesses()).isEqualTo(auditBefore);
    }

    @Test
    @DisplayName("SEC-011: ID malformado responde 400 nos dois verbos")
    void shouldAnswer400ForAMalformedId() throws Exception {
        AdminBrowser admin = fullAdmin();

        for (Verb verb : VERBS) {
            assertThat(verb.call()
                            .apply(admin, PRODUCTS + "/not-a-uuid/publication")
                            .statusCode())
                    .as(verb.name())
                    .isEqualTo(400);
        }
    }

    @Test
    @DisplayName("SEC-011: publicar o Produto A não toca o Produto B")
    void shouldNotTouchAnotherProduct() throws Exception {
        UUID other = AdminProductFixtures.insertProductWithFile(jdbc);
        Map<String, Object> otherBefore = productRow(other);
        AdminBrowser admin = fullAdmin();

        assertThat(admin.put(path(existing), "").statusCode()).isEqualTo(200);

        assertThat(productRow(other)).isEqualTo(otherBefore);
        assertThat(productRow(existing)).containsEntry("status", "PUBLISHED");
    }
}
