package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.priceJson;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.validBody;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.validUpdate;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
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
 * SEC-010, SEC-011, SEC-017: who may use the Product routes, by state of the session, with a real login. A denied
 * answer is the same whether the Product exists or not.
 */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-product-authorization", "app.login-limit.ip-max-attempts=1000"})
class AdminProductAuthorizationTest {

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
        jdbc.update("delete from products");
        account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        customerEmail = "cliente-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, ?, now())",
                customerEmail,
                passwordEncoder.encode(PASSWORD));
        existing = jdbc.queryForObject(
                "insert into products (title, description, category, price_cents)"
                        + " values ('Existente', 'd', 'LAW', 500) returning id",
                UUID.class);
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from products", Integer.class);
    }

    /** A call on one route; the argument is the id used in the path ({id} routes only). */
    private record Route(String name, Function<AdminBrowser, Function<UUID, HttpResponse<String>>> call) {}

    private static Route route(String name, Call call) {
        return new Route(name, browser -> id -> {
            try {
                return call.send(browser, id);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @FunctionalInterface
    private interface Call {
        HttpResponse<String> send(AdminBrowser browser, UUID id) throws Exception;
    }

    private static final Route[] ROUTES = {
        route("GET list", (b, id) -> b.get(PRODUCTS)),
        route("GET {id}", (b, id) -> b.get(PRODUCTS + "/" + id)),
        route("POST", (b, id) -> b.post(PRODUCTS, validBody())),
        route("PUT {id}", (b, id) -> b.put(PRODUCTS + "/" + id, validUpdate(0))),
        route("PUT {id}/price", (b, id) -> b.put(PRODUCTS + "/" + id + "/price", priceJson(5000, 0))),
    };

    private static final Route[] DENIED_EVEN_FOR_ADMIN = {
        route("DELETE {id}", (b, id) -> b.delete(PRODUCTS + "/" + id)),
        route("GET {id}/file", (b, id) -> b.get(PRODUCTS + "/" + id + "/file")),
        route("POST {id}/file", (b, id) -> b.post(PRODUCTS + "/" + id + "/file", "{}")),
    };

    private AdminBrowser anonymous() {
        return new AdminBrowser(port);
    }

    private AdminBrowser customer() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);
        return browser;
    }

    private AdminBrowser passwordOnly() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginAdmin(account.email(), PASSWORD).statusCode()).isEqualTo(204);
        return browser;
    }

    private AdminBrowser expiredPending() throws Exception {
        AdminBrowser browser = passwordOnly();
        clock.advance(Duration.ofMinutes(10));
        return browser;
    }

    private AdminBrowser fullAdmin() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
        return browser;
    }

    /** The default error body carries the time of the answer and the path asked; everything else must match. */
    private static String withoutTimestamp(String body) {
        return body.replaceAll("\"(timestamp|path)\":\"[^\"]*\"", "\"$1\":\"\"");
    }

    private void assertDenied(AdminBrowser browser, int status, String state) {
        for (Route route : ROUTES) {
            HttpResponse<String> forExisting = route.call().apply(browser).apply(existing);
            HttpResponse<String> forMissing = route.call().apply(browser).apply(UUID.randomUUID());
            assertThat(forExisting.statusCode()).as(state + " " + route.name()).isEqualTo(status);
            assertThat(forMissing.statusCode()).as(state + " " + route.name()).isEqualTo(status);
            // same body whether the Product exists or not, and no Product data in it
            assertThat(withoutTimestamp(forMissing.body()))
                    .as(state + " " + route.name())
                    .isEqualTo(withoutTimestamp(forExisting.body()));
            assertThat(withoutTimestamp(forExisting.body()))
                    .doesNotContain("Existente")
                    .doesNotContain(existing.toString());
        }
    }

    @Test
    @DisplayName("SEC-010 SEC-011: anônimo recebe 401 em todas as rotas, igual para ID existente e inexistente")
    void shouldAnswer401ToAnonymous() {
        int before = count();

        assertDenied(anonymous(), 401, "anonymous");

        assertThat(count()).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-010 SEC-011: Cliente recebe 403 em todas as rotas, igual para ID existente e inexistente")
    void shouldAnswer403ToCustomer() throws Exception {
        int before = count();

        assertDenied(customer(), 403, "customer");

        assertThat(count()).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-010 SEC-001: só a senha (sem TOTP) recebe 401 em todas as rotas")
    void shouldAnswer401ToPasswordOnly() throws Exception {
        int before = count();

        assertDenied(passwordOnly(), 401, "password only");

        assertThat(count()).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-010: pendente vencido recebe 401 em todas as rotas")
    void shouldAnswer401ToExpiredPending() throws Exception {
        int before = count();

        assertDenied(expiredPending(), 401, "expired pending");

        assertThat(count()).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-017: PUT sem o token CSRF dá 403 nas duas rotas e nada muda")
    void shouldRefuseAPutWithoutCsrfToken() throws Exception {
        AdminBrowser admin = fullAdmin();

        assertThat(admin.putWithoutCsrf(PRODUCTS + "/" + existing, validUpdate(0))
                        .statusCode())
                .isEqualTo(403);
        assertThat(admin.putWithoutCsrf(PRODUCTS + "/" + existing + "/price", priceJson(5000, 0))
                        .statusCode())
                .isEqualTo(403);
        assertThat(jdbc.queryForMap("select title, price_cents, version from products where id = ?", existing))
                .containsEntry("title", "Existente")
                .containsEntry("price_cents", 500)
                .containsEntry("version", 0);
    }

    @Test
    @DisplayName("SEC-010 FR-016: o Administrador completo edita (200) e troca o preço (200); 404 para ID inexistente")
    void shouldLetTheFullAdministratorEdit() throws Exception {
        AdminBrowser admin = fullAdmin();

        assertThat(admin.put(PRODUCTS + "/" + existing, validUpdate(0)).statusCode())
                .isEqualTo(200);
        assertThat(admin.put(PRODUCTS + "/" + existing + "/price", priceJson(5000, 1))
                        .statusCode())
                .isEqualTo(200);
        assertThat(admin.put(PRODUCTS + "/" + UUID.randomUUID(), validUpdate(0)).statusCode())
                .isEqualTo(404);
        assertThat(admin.put(PRODUCTS + "/" + UUID.randomUUID() + "/price", priceJson(5000, 0))
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("SEC-010: o Administrador completo chega às rotas (200, 200, 201), e 404 para ID inexistente")
    void shouldLetTheFullAdministratorIn() throws Exception {
        AdminBrowser admin = fullAdmin();

        assertThat(admin.get(PRODUCTS).statusCode()).isEqualTo(200);
        assertThat(admin.get(PRODUCTS + "/" + existing).statusCode()).isEqualTo(200);
        assertThat(admin.get(PRODUCTS + "/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(admin.post(PRODUCTS, validBody()).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("SEC-017: POST sem o token CSRF dá 403 e nada é gravado")
    void shouldRefuseAPostWithoutCsrfToken() throws Exception {
        AdminBrowser admin = fullAdmin();
        int before = count();

        HttpResponse<String> response = admin.postWithoutCsrf(PRODUCTS, validBody());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(count()).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-010 SEC-020: DELETE e o arquivo do Produto dão 403 até para o Administrador completo")
    void shouldKeepDeleteAndFileRoutesDenied() throws Exception {
        AdminBrowser admin = fullAdmin();
        int before = count();

        for (Route route : DENIED_EVEN_FOR_ADMIN) {
            assertThat(route.call().apply(admin).apply(existing).statusCode())
                    .as(route.name())
                    .isEqualTo(403);
            assertThat(route.call().apply(admin).apply(UUID.randomUUID()).statusCode())
                    .as(route.name() + " unknown")
                    .isEqualTo(403);
        }
        assertThat(admin.get(PRODUCTS + "/" + existing + "/x/y").statusCode()).isEqualTo(403);
        assertThat(count()).isEqualTo(before);
    }
}
