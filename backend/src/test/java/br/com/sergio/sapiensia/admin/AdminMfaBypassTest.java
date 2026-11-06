package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The route/state table of the plan (Risk a): no state short of the full login reaches the panel. */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-mfa-bypass", "app.login-limit.ip-max-attempts=1000"})
class AdminMfaBypassTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow
    private static final String CURRENT = "/api/admin/sessions/current";
    private static final String MFA = "/api/admin/sessions/mfa";
    private static final String ENROLLMENT = MFA + "/enrollment";
    private static final String CONFIRMATION = ENROLLMENT + "/confirmation";

    enum State {
        ANONYMOUS,
        CUSTOMER,
        PENDING,
        PENDING_EXPIRED,
        FULL_ADMINISTRATOR
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

    private String email;
    private String customerEmail;
    private byte[] secret;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from administrators");
        email = "painel-" + UUID.randomUUID() + "@example.com";
        customerEmail = "cliente-" + UUID.randomUUID() + "@example.com";
        UUID adminId = UUID.randomUUID();
        jdbc.update(
                "insert into administrators (id, email, password_hash) values (?, ?, ?)",
                adminId,
                email,
                passwordEncoder.encode(PASSWORD));
        jdbc.update(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, ?, now())",
                customerEmail,
                passwordEncoder.encode(PASSWORD));
        secret = AdminMfaTestSupport.enableMfa(jdbc, cipher, adminId);
    }

    private AdminBrowser browserIn(State state) throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        switch (state) {
            case ANONYMOUS -> {}
            case CUSTOMER ->
                assertThat(browser.loginCustomer(customerEmail, PASSWORD).statusCode())
                        .isEqualTo(204);
            case PENDING ->
                assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
            case PENDING_EXPIRED -> {
                assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
                clock.advance(Duration.ofMinutes(5).plusSeconds(1));
            }
            case FULL_ADMINISTRATOR ->
                assertThat(browser.loginAdminFully(email, PASSWORD, secret, clock)
                                .statusCode())
                        .isEqualTo(204);
        }
        return browser;
    }

    static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of(State.PENDING, "GET", CURRENT, 401),
                Arguments.of(State.PENDING, "GET", "/api/admin/qualquer-coisa", 401),
                Arguments.of(State.PENDING, "POST", "/api/admin/qualquer-coisa", 401),
                Arguments.of(State.PENDING, "GET", "/api/sessions/current", 401),
                Arguments.of(State.PENDING, "DELETE", "/api/sessions", 401),
                Arguments.of(State.PENDING_EXPIRED, "GET", CURRENT, 401),
                Arguments.of(State.PENDING_EXPIRED, "GET", "/api/admin/qualquer-coisa", 401),
                Arguments.of(State.PENDING_EXPIRED, "POST", "/api/admin/qualquer-coisa", 401),
                Arguments.of(State.PENDING_EXPIRED, "GET", "/api/sessions/current", 401),
                Arguments.of(State.PENDING_EXPIRED, "DELETE", "/api/sessions", 401),
                Arguments.of(State.PENDING_EXPIRED, "POST", MFA, 401),
                Arguments.of(State.CUSTOMER, "POST", MFA, 403),
                Arguments.of(State.CUSTOMER, "GET", CURRENT, 403),
                Arguments.of(State.CUSTOMER, "GET", "/api/admin/qualquer-coisa", 403),
                Arguments.of(State.ANONYMOUS, "GET", CURRENT, 401),
                Arguments.of(State.ANONYMOUS, "GET", "/api/admin/qualquer-coisa", 401),
                Arguments.of(State.ANONYMOUS, "POST", "/api/admin/qualquer-coisa", 401),
                Arguments.of(State.ANONYMOUS, "GET", "/api/sessions/current", 401),
                Arguments.of(State.ANONYMOUS, "DELETE", "/api/sessions", 401),
                Arguments.of(State.ANONYMOUS, "POST", MFA, 401),
                Arguments.of(State.FULL_ADMINISTRATOR, "POST", MFA, 403),
                Arguments.of(State.FULL_ADMINISTRATOR, "GET", CURRENT, 200),
                Arguments.of(State.ANONYMOUS, "GET", MFA, 401),
                Arguments.of(State.ANONYMOUS, "POST", ENROLLMENT, 401),
                Arguments.of(State.ANONYMOUS, "POST", CONFIRMATION, 401),
                Arguments.of(State.CUSTOMER, "GET", MFA, 403),
                Arguments.of(State.CUSTOMER, "POST", ENROLLMENT, 403),
                Arguments.of(State.CUSTOMER, "POST", CONFIRMATION, 403),
                Arguments.of(State.FULL_ADMINISTRATOR, "GET", MFA, 403),
                Arguments.of(State.FULL_ADMINISTRATOR, "POST", ENROLLMENT, 403),
                Arguments.of(State.FULL_ADMINISTRATOR, "POST", CONFIRMATION, 403),
                Arguments.of(State.PENDING_EXPIRED, "GET", MFA, 401),
                Arguments.of(State.PENDING_EXPIRED, "POST", ENROLLMENT, 401),
                Arguments.of(State.PENDING_EXPIRED, "POST", CONFIRMATION, 401),
                Arguments.of(State.FULL_ADMINISTRATOR, "GET", "/api/admin/qualquer-coisa", 403));
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @MethodSource("routes")
    @DisplayName("SEC-001 / SEC-010: cada estado recebe na rota só a resposta da tabela do plano")
    void shouldAnswerEachRouteAsTheTableSays(State state, String method, String path, int expected) throws Exception {
        AdminBrowser browser = browserIn(state);

        HttpResponse<String> response = switch (method) {
            case "GET" -> browser.get(path);
            case "POST" -> browser.post(path, "{\"code\":\"000000\"}");
            case "DELETE" -> browser.delete(path);
            default -> throw new IllegalArgumentException(method);
        };

        assertThat(response.statusCode()).isEqualTo(expected);
        if (expected != 200) {
            assertThat(response.body()).doesNotContain(email).doesNotContain("PANEL_ACCESS");
        }
    }

    @Test
    @DisplayName("SEC-010: definir a senha nunca cria sessão")
    void shouldNotCreateASessionOnPasswordSetup() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);

        HttpResponse<String> setup = browser.post(
                "/api/admin/password-setup",
                "{\"token\":\"token-que-nao-existe\",\"newPassword\":\"outra-senha-do-painel-9\"}");

        assertThat(setup.statusCode()).isEqualTo(400);
        assertThat(setup.headers().allValues("set-cookie")).noneMatch(c -> c.startsWith("JSESSIONID"));
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-010: o login do Cliente troca a sessão pendente por uma de Cliente, sem acesso ao painel")
    void shouldReplaceThePendingSessionWithACustomerSession() throws Exception {
        AdminBrowser browser = browserIn(State.PENDING);
        String pending = browser.cookie("JSESSIONID");

        assertThat(browser.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);

        assertThat(browser.cookie("JSESSIONID")).isNotEqualTo(pending);
        assertThat(browser.get("/api/sessions/current").statusCode()).isEqualTo(200);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(403);
        assertThat(browser.post(MFA, "{\"code\":\"" + AdminMfaTestSupport.codeAt(secret, clock.instant()) + "\"}")
                        .statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("SEC-001: a senha de novo com o Administrador completo volta ao estado pendente")
    void shouldGoBackToPendingWhenTheFullAdministratorLogsInAgain() throws Exception {
        AdminBrowser browser = browserIn(State.FULL_ADMINISTRATOR);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(200);

        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);

        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-001: o logout da sessão pendente dá 204 e encerra a pendência")
    void shouldEndThePendingStateOnLogout() throws Exception {
        AdminBrowser browser = browserIn(State.PENDING);

        assertThat(browser.delete("/api/sessions/current").statusCode()).isEqualTo(204);

        String code = AdminMfaTestSupport.codeAt(secret, clock.instant());
        assertThat(browser.verifyMfa(code).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-001: a senha de novo na sessão pendente recomeça a etapa e a sessão antiga morre")
    void shouldRestartThePendingStepOnANewPassword() throws Exception {
        AdminBrowser browser = browserIn(State.PENDING);
        String oldSession = browser.cookie("JSESSIONID");
        String oldXsrf = browser.cookie("XSRF-TOKEN");

        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);

        assertThat(browser.cookie("JSESSIONID")).isNotEqualTo(oldSession);
        AdminBrowser replay = new AdminBrowser(port);
        replay.setCookie("JSESSIONID", oldSession);
        replay.setCookie("XSRF-TOKEN", oldXsrf);
        String code = AdminMfaTestSupport.codeAt(secret, clock.instant());
        assertThat(replay.verifyMfa(code).statusCode()).isEqualTo(401);
        assertThat(browser.verifyMfa(code).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-001: o principal pendente só tem o fator senha, nunca a capacidade do painel")
    void shouldNeverGiveThePendingPrincipalThePanelCapability() {
        var pending = new PendingAdministrator(UUID.randomUUID(), "x@example.com", 0, Instant.now());

        assertThat(pending.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("FACTOR_PASSWORD")
                .doesNotContain(Capability.PANEL_ACCESS.authority());
        assertThat(pending.toString()).doesNotContain("x@example.com");
    }
}
