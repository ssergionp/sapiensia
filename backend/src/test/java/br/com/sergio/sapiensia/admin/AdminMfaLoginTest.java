package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import br.com.sergio.sapiensia.security.Totp;
import java.net.http.HttpResponse;
import java.time.Duration;
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

/** The two-step panel login against the real server: password, then the TOTP code (own context: limiter, clock). */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-mfa-login", "app.login-limit.ip-max-attempts=1000"})
class AdminMfaLoginTest {

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

    private String email;
    private UUID adminId;
    private byte[] secret;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from administrators");
        email = "painel-" + UUID.randomUUID() + "@example.com";
        adminId = UUID.randomUUID();
        jdbc.update(
                "insert into administrators (id, email, password_hash) values (?, ?, ?)",
                adminId,
                email,
                passwordEncoder.encode(PASSWORD));
        secret = AdminMfaTestSupport.enableMfa(jdbc, cipher, adminId);
        // every test starts on a step no other test used
        clock.advance(Duration.ofMinutes(1));
    }

    private AdminBrowser browser() {
        return new AdminBrowser(port);
    }

    private String code() {
        return AdminMfaTestSupport.codeAt(secret, clock.instant());
    }

    private String wrongCode() {
        return AdminMfaTestSupport.wrongCodeAt(secret, clock.instant());
    }

    private void newCustomer(String customerEmail) {
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at) values (?, ?, ?, now())",
                UUID.randomUUID(),
                customerEmail,
                passwordEncoder.encode(PASSWORD));
    }

    @Test
    @DisplayName("SEC-001: a senha certa dá 204 mas ainda não abre o painel")
    void shouldLeaveThePasswordStepPending() throws Exception {
        AdminBrowser browser = browser();

        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);

        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-001: o código certo dá 204, troca a sessão e o CSRF e abre o painel")
    void shouldCompleteTheLoginWithTheRightCode() throws Exception {
        AdminBrowser browser = browser();
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        String pendingSession = browser.cookie("JSESSIONID");
        String pendingXsrf = browser.cookie("XSRF-TOKEN");

        HttpResponse<String> mfa = browser.verifyMfa(code());

        assertThat(mfa.statusCode()).isEqualTo(204);
        assertThat(mfa.body()).isEmpty();
        // the old pending session is dead: replayed in a fresh request it opens nothing
        AdminBrowser replay = browser();
        replay.setCookie("JSESSIONID", pendingSession);
        replay.setCookie("XSRF-TOKEN", pendingXsrf);
        assertThat(replay.get(CURRENT).statusCode()).isEqualTo(401);
        assertThat(replay.verifyMfa(code()).statusCode()).isEqualTo(401);
        assertThat(browser.cookie("JSESSIONID")).isNotBlank().isNotEqualTo(pendingSession);
        assertThat(browser.cookie("XSRF-TOKEN")).isNotBlank().isNotEqualTo(pendingXsrf);
        HttpResponse<String> current = browser.get(CURRENT);
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(current.body())
                .isEqualTo(
                        "{\"email\":\"" + email
                                + "\",\"capabilities\":[\"PANEL_ACCESS\",\"RECOVERY_CODES_MANAGE\",\"PRODUCTS_EDIT\",\"PRODUCTS_PRICE\",\"PRODUCTS_FILE\",\"PRODUCTS_PUBLISH\",\"ORDERS_VIEW\",\"CUSTOMERS_ANONYMIZE\"]}");
    }

    @Test
    @DisplayName("SEC-001: código errado dá 400 CODE_INVALID e o painel continua fechado")
    void shouldRefuseAWrongCode() throws Exception {
        AdminBrowser browser = browser();
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);

        HttpResponse<String> mfa = browser.verifyMfa(wrongCode());

        assertThat(mfa.statusCode()).isEqualTo(400);
        assertThat(mfa.body()).contains("\"errors\":{\"code\":\"CODE_INVALID\"}");
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-001: código com 5 ou 7 dígitos dá 400")
    void shouldRefuseCodesOfTheWrongLength() throws Exception {
        AdminBrowser browser = browser();
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        String right = code();

        assertThat(browser.verifyMfa(right.substring(0, 5)).statusCode()).isEqualTo(400);
        assertThat(browser.verifyMfa(right + "0").statusCode()).isEqualTo(400);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-001: 5 min e 1 s depois da senha a etapa pendente venceu e o código recebe 401")
    void shouldExpireThePendingStep() throws Exception {
        AdminBrowser browser = browser();
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(browser.verifyMfa(code()).statusCode()).isEqualTo(401);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-001: 4 min e 59 s depois da senha o código ainda vale")
    void shouldAcceptTheCodeJustBeforeThePendingStepExpires() throws Exception {
        AdminBrowser browser = browser();
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);

        clock.advance(Duration.ofMinutes(4).plusSeconds(59));

        assertThat(browser.verifyMfa(code()).statusCode()).isEqualTo(204);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-001: o código do passo anterior ao último aceito dá 400")
    void shouldRefuseTheCodeOfAnEarlierStep() throws Exception {
        assertThat(browser().loginAdminFully(email, PASSWORD, secret, clock).statusCode())
                .isEqualTo(204);
        long accepted = Totp.stepAt(clock.instant());
        AdminBrowser browser = browser();
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);

        // still inside the accepted window (t-1), but not after the last accepted step
        assertThat(browser.verifyMfa(Totp.code(secret, accepted - 1, 6)).statusCode())
                .isEqualTo(400);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-002: 5 códigos errados dão 429 com Retry-After, inclusive para o código certo")
    void shouldLockTheCodeAfterFiveFailures() throws Exception {
        AdminBrowser browser = browser();
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        for (int i = 0; i < 5; i++) {
            assertThat(browser.verifyMfa(wrongCode()).statusCode()).isEqualTo(400);
        }

        HttpResponse<String> locked = browser.verifyMfa(code());

        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
        // a new password step does not reset the account's code failures
        AdminBrowser again = browser();
        assertThat(again.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        assertThat(again.verifyMfa(code()).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("SEC-002: o bloqueio do código não bloqueia o login por senha do Cliente com o mesmo e-mail")
    void shouldNotLetTheCodeLockBlockTheCustomer() throws Exception {
        newCustomer(email);
        AdminBrowser browser = browser();
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        for (int i = 0; i < 5; i++) {
            assertThat(browser.verifyMfa(wrongCode()).statusCode()).isEqualTo(400);
        }
        assertThat(browser.verifyMfa(code()).statusCode()).isEqualTo(429);

        assertThat(browser().loginCustomer(email, PASSWORD).statusCode()).isEqualTo(204);
        assertThat(browser().loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-002: erros de senha do Cliente com o mesmo e-mail não bloqueiam o código do Administrador")
    void shouldNotLetCustomerFailuresBlockTheCode() throws Exception {
        newCustomer(email);
        for (int i = 0; i < 5; i++) {
            assertThat(browser().loginCustomer(email, WRONG).statusCode()).isEqualTo(401);
        }

        assertThat(browser().loginAdminFully(email, PASSWORD, secret, clock).statusCode())
                .isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-006: sem a linha do Administrador, a sessão pendente recebe 401 no código")
    void shouldEndThePendingSessionOfADeletedAdministrator() throws Exception {
        AdminBrowser browser = browser();
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        String code = code();

        jdbc.update("delete from administrators where id = ?", adminId);

        assertThat(browser.verifyMfa(code).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-001: sem MFA ativo o código dá 409 e não conta como erro de código")
    void shouldAnswer409WithoutCountingWhenMfaIsNotActive() throws Exception {
        jdbc.update("update administrators set totp_enabled_at = null where id = ?", adminId);
        AdminBrowser browser = browser();
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        for (int i = 0; i < 6; i++) {
            assertThat(browser.verifyMfa(code()).statusCode()).isEqualTo(409);
        }

        jdbc.update("update administrators set totp_enabled_at = now() where id = ?", adminId);

        assertThat(browser.verifyMfa(code()).statusCode()).isEqualTo(204);
    }
}
