package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import br.com.sergio.sapiensia.security.Tokens;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Real server, own context (limiter state is in memory) and its own cookie jar per "browser". */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-session", "app.login-limit.ip-max-attempts=1000"})
class AdminSessionTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow
    private static final String WRONG = "senha-errada-45678";
    private static final String CURRENT = "/api/admin/sessions/current";

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

    @MockitoSpyBean
    Administrators administrators;

    private String email;
    private UUID adminId;
    private byte[] secret;

    @BeforeEach
    void setUp() {
        reset(administrators);
        jdbc.update("delete from administrators");
        email = "painel-" + UUID.randomUUID() + "@example.com";
        adminId = UUID.randomUUID();
        jdbc.update(
                "insert into administrators (id, email, password_hash) values (?, ?, ?)",
                adminId,
                email,
                passwordEncoder.encode(PASSWORD));
        secret = AdminMfaTestSupport.enableMfa(jdbc, cipher, adminId);
    }

    private AdminBrowser browser() {
        return new AdminBrowser(port);
    }

    private String newCustomer(String customerEmail) {
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at) values (?, ?, ?, now())",
                UUID.randomUUID(),
                customerEmail,
                passwordEncoder.encode(PASSWORD));
        return customerEmail;
    }

    // Date and X-Correlation-Id change on every response by design; every other header must be identical
    private static Map<String, String> headersWithoutPerRequestValues(HttpResponse<?> response) {
        Map<String, String> headers = new TreeMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("date") && !name.equalsIgnoreCase("x-correlation-id")) {
                headers.put(name.toLowerCase(), String.join(",", values));
            }
        });
        return headers;
    }

    @Test
    @DisplayName("SEC-010: senha certa dá 204, o painel responde com as capacidades e os cookies são novos")
    void shouldLogTheAdministratorIn() throws Exception {
        AdminBrowser browser = browser();
        browser.get("/entrar");
        String xsrfBefore = browser.cookie("XSRF-TOKEN");
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);

        HttpResponse<String> login = browser.loginAdminFully(email, PASSWORD, secret, clock);

        assertThat(login.statusCode()).isEqualTo(204);
        assertThat(login.body()).isEmpty();
        assertThat(browser.cookie("JSESSIONID")).isNotBlank();
        assertThat(browser.cookie("XSRF-TOKEN")).isNotBlank().isNotEqualTo(xsrfBefore);
        HttpResponse<String> current = browser.get(CURRENT);
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(current.body())
                .isEqualTo(
                        "{\"email\":\"" + email
                                + "\",\"capabilities\":[\"PANEL_ACCESS\",\"RECOVERY_CODES_MANAGE\",\"PRODUCTS_EDIT\",\"PRODUCTS_PRICE\",\"PRODUCTS_FILE\",\"PRODUCTS_PUBLISH\",\"ORDERS_VIEW\",\"REFUNDS_VIEW\",\"CUSTOMERS_ANONYMIZE\",\"AUTHENTICATOR_REPLACE\"],\"oldCodeRequired\":true}");
    }

    @Test
    @DisplayName("SEC-010: o login do painel sem o token CSRF dá 403 e não cria sessão")
    void shouldRejectTheLoginWithoutTheCsrfToken() throws Exception {
        HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                .send(
                        java.net.http.HttpRequest.newBuilder(
                                        java.net.URI.create("http://localhost:" + port + "/api/admin/sessions"))
                                .header("Content-Type", "application/json")
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                        "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().allValues("set-cookie")).noneMatch(c -> c.startsWith("JSESSIONID"));
    }

    @Test
    @DisplayName("SEC-010 / SEC-002: e-mail errado, senha errada e senha não definida dão o mesmo 401 vazio")
    void shouldAnswerTheSameForEveryFailure() throws Exception {
        String noPasswordEmail = "sem-senha-" + UUID.randomUUID() + "@example.com";
        jdbc.update("insert into administrators (email) values (?)", noPasswordEmail);

        AdminBrowser a = browser();
        HttpResponse<String> wrongEmail = a.loginAdmin("ninguem-" + UUID.randomUUID() + "@example.com", PASSWORD);
        HttpResponse<String> wrongPassword = a.loginAdmin(email, WRONG);
        HttpResponse<String> notSet = a.loginAdmin(noPasswordEmail, PASSWORD);

        for (HttpResponse<String> response : List.of(wrongEmail, wrongPassword, notSet)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(headersWithoutPerRequestValues(response)).isEqualTo(headersWithoutPerRequestValues(wrongEmail));
        }
        assertThat(a.cookie("JSESSIONID")).isNull();
    }

    @Test
    @DisplayName("SEC-002: 5 erros bloqueiam com 429 e Retry-After, e o Cliente com o mesmo e-mail continua entrando")
    void shouldLockOutTheAdministratorWithoutLockingTheCustomer() throws Exception {
        newCustomer(email);
        AdminBrowser browser = browser();
        for (int i = 0; i < 5; i++) {
            assertThat(browser.loginAdmin(email, WRONG).statusCode()).isEqualTo(401);
        }

        HttpResponse<String> locked = browser.loginAdmin(email, PASSWORD);

        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        assertThat(browser().loginCustomer(email, PASSWORD).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-002: espaços e maiúsculas no e-mail não dão um balde novo de tentativas")
    void shouldNotLetADifferentSpellingOfTheEmailBypassTheLockout() throws Exception {
        AdminBrowser browser = browser();
        String[] variants = {
            email, " " + email.toUpperCase() + " ", "\\t" + email.toUpperCase() + "\\t", email + " ", "  " + email
        };
        // the five wrong passwords are spread over the spellings: together they fill ONE bucket
        for (String variant : variants) {
            assertThat(browser.loginAdmin(variant, WRONG).statusCode()).isEqualTo(401);
        }

        for (String variant : variants) {
            assertThat(browser.loginAdmin(variant, PASSWORD).statusCode()).isEqualTo(429);
        }
    }

    @Test
    @DisplayName("SEC-002: as grafias de um e-mail inexistente também dividem um balde só")
    void shouldShareOneBucketAmongTheSpellingsOfAnUnknownEmail() throws Exception {
        String unknown = "ninguem-" + UUID.randomUUID() + "@example.com";
        String[] variants = {
            unknown, " " + unknown.toUpperCase(), "\\t" + unknown + "\\t", unknown.toUpperCase(), unknown + " "
        };
        AdminBrowser browser = browser();
        for (String variant : variants) {
            assertThat(browser.loginAdmin(variant, WRONG).statusCode()).isEqualTo(401);
        }

        for (String variant : variants) {
            assertThat(browser.loginAdmin(variant, WRONG).statusCode()).isEqualTo(429);
        }
    }

    @Test
    @DisplayName("SEC-010: Cliente logado que erra o login do painel continua com a sessão de Cliente")
    void shouldKeepTheCustomerSessionWhenThePanelLoginFails() throws Exception {
        String customerEmail = newCustomer("cliente-" + UUID.randomUUID() + "@example.com");
        AdminBrowser browser = browser();
        assertThat(browser.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);
        String session = browser.cookie("JSESSIONID");

        assertThat(browser.loginAdmin(email, WRONG).statusCode()).isEqualTo(401);

        assertThat(browser.cookie("JSESSIONID")).isEqualTo(session);
        assertThat(browser.get("/api/sessions/current").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-002: erros do Cliente não bloqueiam o Administrador com o mesmo e-mail")
    void shouldNotLetCustomerFailuresLockTheAdministrator() throws Exception {
        newCustomer(email);
        AdminBrowser browser = browser();
        for (int i = 0; i < 5; i++) {
            assertThat(browser.loginCustomer(email, WRONG).statusCode()).isEqualTo(401);
        }

        assertThat(browser().loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-010: falha do banco no login dá 503 e não conta como senha errada")
    void shouldAnswer503AndNotCountWhenTheDatabaseFails() throws Exception {
        doThrow(new DataAccessResourceFailureException("down"))
                .when(administrators)
                .findForLogin(any());
        AdminBrowser browser = browser();
        for (int i = 0; i < 6; i++) {
            assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(503);
        }
        reset(administrators);

        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-010: entradas grandes demais dão 401 sem tocar no limitador")
    void shouldRefuseOversizedInputWithoutCounting() throws Exception {
        AdminBrowser browser = browser();
        for (int i = 0; i < 6; i++) {
            assertThat(browser.loginAdmin(email, "x".repeat(129)).statusCode()).isEqualTo(401);
            assertThat(browser.loginAdmin("a".repeat(250) + "@example.com", PASSWORD)
                            .statusCode())
                    .isEqualTo(401);
        }

        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-010: sessão de Cliente recebe 403 no painel, e a de Administrador 403 nas rotas do Cliente")
    void shouldKeepTheTwoKindsOfSessionApart() throws Exception {
        String customerEmail = newCustomer("cliente-" + UUID.randomUUID() + "@example.com");
        AdminBrowser customer = browser();
        assertThat(customer.get(CURRENT).statusCode()).isEqualTo(401);
        assertThat(customer.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);
        assertThat(customer.get(CURRENT).statusCode()).isEqualTo(403);

        AdminBrowser admin = browser();
        assertThat(admin.loginAdminFully(email, PASSWORD, secret, clock).statusCode())
                .isEqualTo(204);
        assertThat(admin.get("/api/sessions/current").statusCode()).isEqualTo(403);
        assertThat(admin.delete("/api/sessions").statusCode()).isEqualTo(403);
        assertThat(admin.get(CURRENT).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-010 / SEC-006: no mesmo navegador o login do painel troca a sessão do Cliente por inteiro")
    void shouldReplaceTheCustomerSessionWhenTheAdministratorLogsIn() throws Exception {
        String customerEmail = newCustomer("cliente-" + UUID.randomUUID() + "@example.com");
        AdminBrowser browser = browser();
        assertThat(browser.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);
        assertThat(browser.get("/api/sessions/current").statusCode()).isEqualTo(200);
        String customerSession = browser.cookie("JSESSIONID");
        String customerXsrf = browser.cookie("XSRF-TOKEN");

        assertThat(browser.loginAdminFully(email, PASSWORD, secret, clock).statusCode())
                .isEqualTo(204);

        assertThat(browser.cookie("JSESSIONID")).isNotBlank().isNotEqualTo(customerSession);
        assertThat(browser.cookie("XSRF-TOKEN")).isNotBlank().isNotEqualTo(customerXsrf);
        assertThat(browser.get("/api/sessions/current").statusCode()).isEqualTo(403);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-010 / SEC-006: no mesmo navegador o login do Cliente troca a sessão do Administrador")
    void shouldReplaceTheAdministratorSessionWhenTheCustomerLogsIn() throws Exception {
        String customerEmail = newCustomer("cliente-" + UUID.randomUUID() + "@example.com");
        AdminBrowser browser = browser();
        assertThat(browser.loginAdminFully(email, PASSWORD, secret, clock).statusCode())
                .isEqualTo(204);
        String adminSession = browser.cookie("JSESSIONID");

        assertThat(browser.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);

        assertThat(browser.cookie("JSESSIONID")).isNotBlank().isNotEqualTo(adminSession);
        assertThat(browser.get("/api/sessions/current").statusCode()).isEqualTo(200);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("SEC-007: um link que sobrou não troca a senha já definida, e a sessão do Administrador continua")
    void shouldRefuseALeftoverLinkOnceThePasswordIsSet() throws Exception {
        AdminBrowser browser = browser();
        assertThat(browser.loginAdminFully(email, PASSWORD, secret, clock).statusCode())
                .isEqualTo(204);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(200);
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update(
                "insert into administrator_password_tokens (administrator_id, token_hash, expires_at)"
                        + " values (?, ?, now() + interval '1 hour')",
                adminId,
                Tokens.hash(token));

        HttpResponse<String> setup = browser()
                .post(
                        "/api/admin/password-setup",
                        "{\"token\":\"" + token + "\",\"newPassword\":\"outra-senha-do-painel-9\"}");

        assertThat(setup.statusCode()).isEqualTo(400);
        assertThat(setup.body()).contains("TOKEN_INVALID");
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(200);
        assertThat(browser().loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName(
            "SEC-006: quando a session_version do Administrador sobe (definir a senha a sobe), a sessão antiga recebe 401")
    void shouldEndTheSessionWhenTheSessionVersionChanges() throws Exception {
        AdminBrowser browser = browser();
        assertThat(browser.loginAdminFully(email, PASSWORD, secret, clock).statusCode())
                .isEqualTo(204);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(200);

        jdbc.update("update administrators set session_version = session_version + 1 where id = ?", adminId);

        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-006: sem a linha do Administrador a sessão recebe 401")
    void shouldEndTheSessionOfADeletedAdministrator() throws Exception {
        AdminBrowser browser = browser();
        assertThat(browser.loginAdminFully(email, PASSWORD, secret, clock).statusCode())
                .isEqualTo(204);

        jdbc.update("delete from administrators where id = ?", adminId);

        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-006: o logout DELETE /api/sessions/current encerra a sessão do Administrador")
    void shouldEndTheAdministratorSessionOnLogout() throws Exception {
        AdminBrowser browser = browser();
        assertThat(browser.loginAdminFully(email, PASSWORD, secret, clock).statusCode())
                .isEqualTo(204);

        assertThat(browser.delete("/api/sessions/current").statusCode()).isEqualTo(204);

        assertThat(browser.cookie("JSESSIONID")).isNull();
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-012: campos extras no corpo do login do painel não mudam as capacidades nem o e-mail")
    void shouldIgnoreCapabilitiesSentInTheLoginBody() throws Exception {
        AdminBrowser browser = browser();

        HttpResponse<String> login = browser.post(
                "/api/admin/sessions",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\","
                        + "\"capabilities\":[\"PRODUCTS_MANAGE\",\"ANYTHING\"],\"role\":\"ADMIN\","
                        + "\"authorities\":[\"ROLE_CUSTOMER\"],\"id\":\"" + UUID.randomUUID() + "\"}");

        assertThat(login.statusCode()).isEqualTo(204);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
        clock.advance(java.time.Duration.ofSeconds(30));
        assertThat(browser.verifyMfa(AdminMfaTestSupport.codeAt(secret, clock.instant()))
                        .statusCode())
                .isEqualTo(204);
        HttpResponse<String> current = browser.get(CURRENT);
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(current.body())
                .isEqualTo(
                        "{\"email\":\"" + email
                                + "\",\"capabilities\":[\"PANEL_ACCESS\",\"RECOVERY_CODES_MANAGE\",\"PRODUCTS_EDIT\",\"PRODUCTS_PRICE\",\"PRODUCTS_FILE\",\"PRODUCTS_PUBLISH\",\"ORDERS_VIEW\",\"REFUNDS_VIEW\",\"CUSTOMERS_ANONYMIZE\",\"AUTHENTICATOR_REPLACE\"],\"oldCodeRequired\":true}");
    }

    @Test
    @DisplayName("SEC-012: Cliente que manda cabeçalho ou corpo com capacidades de administrador continua com 403")
    void shouldNotGrantPanelAccessToACustomerClaimingCapabilities() throws Exception {
        String customerEmail = newCustomer("cliente-" + UUID.randomUUID() + "@example.com");
        AdminBrowser browser = browser();
        assertThat(browser.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);

        HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                .send(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + CURRENT))
                                .header("Cookie", "JSESSIONID=" + browser.cookie("JSESSIONID"))
                                .header("X-Capabilities", "PANEL_ACCESS")
                                .header("Content-Type", "application/json")
                                .method(
                                        "GET",
                                        java.net.http.HttpRequest.BodyPublishers.ofString(
                                                "{\"capabilities\":[\"PANEL_ACCESS\"],\"role\":\"ADMIN\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).doesNotContain("PANEL_ACCESS");
    }

    @Test
    @DisplayName("SEC-010: o log do login não contém o e-mail nem a senha")
    void shouldNotLogTheEmailOrThePassword(CapturedOutput output) throws Exception {
        AdminBrowser browser = browser();
        browser.loginAdmin(email, WRONG);
        browser.loginAdmin(email, PASSWORD);
        doThrow(new DataAccessResourceFailureException("down for " + email))
                .when(administrators)
                .findForLogin(any());
        browser().loginAdmin(email, PASSWORD);

        assertThat(output.getAll())
                .doesNotContain(email)
                .doesNotContain(PASSWORD)
                .doesNotContain(WRONG);
    }

    @Test
    @DisplayName("SEC-001: falha do banco no código dá 503, não conta como erro e o log não traz o código")
    void shouldAnswer503AndNotCountWhenTheDatabaseFailsOnTheCode(CapturedOutput output) throws Exception {
        AdminBrowser browser = browser();
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        clock.advance(java.time.Duration.ofSeconds(30));
        String code = AdminMfaTestSupport.codeAt(secret, clock.instant());
        // only what this test logs: a 6-digit number could appear by chance in earlier startup lines
        int from = output.getAll().length();
        doThrow(new DataAccessResourceFailureException("down for " + code))
                .when(administrators)
                .findTotp(any());
        for (int i = 0; i < 6; i++) {
            assertThat(browser.verifyMfa(code).statusCode()).isEqualTo(503);
        }
        reset(administrators);

        assertThat(browser.verifyMfa(code).statusCode()).isEqualTo(204);
        assertThat(output.getAll().substring(from))
                .contains("MFA check unavailable")
                .doesNotContain(code)
                .doesNotContain(email);
    }

    @Test
    @DisplayName("SEC-001: falha do banco ao ver o status ou iniciar o cadastro do MFA dá 503 e o log não traz segredo")
    void shouldAnswer503WhenTheDatabaseFailsOnTheMfaStatusAndEnrollment(CapturedOutput output) throws Exception {
        AdminBrowser browser = browser();
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        int from = output.getAll().length();
        doThrow(new DataAccessResourceFailureException("down for " + email))
                .when(administrators)
                .findTotp(any());
        doThrow(new DataAccessResourceFailureException("down for " + email))
                .when(administrators)
                .startEnrollment(any(), any());

        assertThat(browser.get("/api/admin/sessions/mfa").statusCode()).isEqualTo(503);
        assertThat(browser.post("/api/admin/sessions/mfa/enrollment", "{}").statusCode())
                .isEqualTo(503);
        reset(administrators);

        assertThat(browser.get("/api/admin/sessions/mfa").statusCode()).isEqualTo(200);
        assertThat(output.getAll().substring(from))
                .contains("MFA check unavailable")
                .doesNotContain(email);
    }

    @Test
    @DisplayName("SEC-001: segredo que não decifra dá 503 e não conta como erro de código")
    void shouldAnswer503WhenTheSecretDoesNotDecrypt() throws Exception {
        String stored =
                jdbc.queryForObject("select totp_secret from administrators where id = ?", String.class, adminId);
        jdbc.update(
                "update administrators set totp_secret = ? where id = ?",
                "v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                adminId);
        AdminBrowser browser = browser();
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        clock.advance(java.time.Duration.ofSeconds(30));
        String code = AdminMfaTestSupport.codeAt(secret, clock.instant());
        for (int i = 0; i < 6; i++) {
            assertThat(browser.verifyMfa(code).statusCode()).isEqualTo(503);
        }

        jdbc.update("update administrators set totp_secret = ? where id = ?", stored, adminId);

        assertThat(browser.verifyMfa(code).statusCode()).isEqualTo(204);
    }
}
