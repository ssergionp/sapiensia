package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import br.com.sergio.sapiensia.security.Totp;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/admin/sessions/reauthentication (SEC-008): password and a fresh TOTP code renew both factors of a full
 * Administrator session. The limits are the login's own (same limiter, same keys), and the code goes through the same
 * replay guard as the login.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import({
    TestcontainersConfiguration.class,
    AdminMfaTestSupport.ClockConfig.class,
    AdminReauthenticationTest.ProbeConfig.class
})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=admin-reauthentication",
            "app.login-limit.ip-max-attempts=1000",
            // the application loggers at their noisiest: nothing of ours may print a password, a code or an e-mail
            "logging.level.br.com.sergio.sapiensia=TRACE"
        })
class AdminReauthenticationTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow
    private static final String WRONG_PASSWORD = "senha-errada-456"; // gitleaks:allow
    private static final String REAUTHENTICATION = "/api/admin/sessions/reauthentication";
    private static final String CREDENTIALS_INVALID = "\"errors\":{\"credentials\":\"CREDENTIALS_INVALID\"}";

    /** Shows the session principal of the caller, so a test can see the stamps the route wrote (outside /api). */
    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe/principal")
        String principal(Authentication authentication) {
            var principal = (AdministratorPrincipal) authentication.getPrincipal();
            return principal.passwordVerifiedAt() + "|" + principal.totpVerifiedAt() + "|" + principal.sessionVersion();
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
    AdministratorRecoveryCodes recoveryCodes;

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
        clock.advance(Duration.ofMinutes(1));
    }

    private AdminBrowser fullSession() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginAdminFully(email, PASSWORD, secret, clock).statusCode())
                .isEqualTo(204);
        return browser;
    }

    /** The code of a step later than any used so far. */
    private String freshCode() {
        clock.advance(Duration.ofSeconds(30));
        return AdminMfaTestSupport.codeAt(secret, clock.instant());
    }

    private static HttpResponse<String> reauthenticate(AdminBrowser browser, String password, String code)
            throws Exception {
        return browser.post(REAUTHENTICATION, "{\"password\":\"" + password + "\",\"code\":\"" + code + "\"}");
    }

    private Long lastStep() {
        return jdbc.queryForObject("select totp_last_step from administrators where id = ?", Long.class, adminId);
    }

    private List<String> reauthenticationAudit() {
        return jdbc.queryForList(
                "select result from audit_events where actor_id = ? and action = 'ADMIN_REAUTHENTICATION'"
                        + " order by occurred_at, id",
                String.class,
                adminId);
    }

    private static void assertCredentialsInvalid(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains(CREDENTIALS_INVALID);
    }

    @Test
    @DisplayName("SEC-008: senha e código certos renovam os dois fatores e trocam a sessão")
    void shouldRenewBothFactorsAndReplaceTheSession() throws Exception {
        AdminBrowser browser = fullSession();
        String oldSession = browser.cookie("JSESSIONID");
        String oldCsrf = browser.cookie("XSRF-TOKEN");
        clock.advance(Duration.ofMinutes(4));
        String code = freshCode();

        HttpResponse<String> response = reauthenticate(browser, PASSWORD, code);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(browser.cookie("JSESSIONID")).isNotNull().isNotEqualTo(oldSession);
        assertThat(browser.cookie("XSRF-TOKEN")).isNotNull().isNotEqualTo(oldCsrf);
        assertThat(browser.get("/api/admin/sessions/current").statusCode()).isEqualTo(200);
        assertThat(browser.get("/probe/principal").body()).isEqualTo(clock.instant() + "|" + clock.instant() + "|0");
        assertThat(lastStep()).isEqualTo(Totp.stepAt(clock.instant()));
        assertThat(reauthenticationAudit()).containsExactly("SUCCESS");
    }

    @Test
    @DisplayName("SEC-008: senha errada responde CREDENTIALS_INVALID sem conferir o código")
    void shouldAnswerCredentialsInvalidForAWrongPasswordWithoutCheckingTheCode() throws Exception {
        AdminBrowser browser = fullSession();
        String code = freshCode();
        Long before = lastStep();

        assertCredentialsInvalid(reauthenticate(browser, WRONG_PASSWORD, code));

        assertThat(lastStep()).isEqualTo(before);
        // the step was not spent: the same code works right after, with the right password
        assertThat(reauthenticate(browser, PASSWORD, code).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-008: código errado ou malformado responde CREDENTIALS_INVALID")
    void shouldAnswerCredentialsInvalidForAWrongOrMalformedCode() throws Exception {
        AdminBrowser browser = fullSession();
        clock.advance(Duration.ofSeconds(30));
        String wrong = AdminMfaTestSupport.wrongCodeAt(secret, clock.instant());

        HttpResponse<String> wrongCode = reauthenticate(browser, PASSWORD, wrong);
        HttpResponse<String> malformed = reauthenticate(browser, PASSWORD, "12ab");
        HttpResponse<String> missing = browser.post(REAUTHENTICATION, "{\"password\":\"" + PASSWORD + "\"}");

        for (HttpResponse<String> response : List.of(wrongCode, malformed, missing)) {
            assertCredentialsInvalid(response);
            assertThat(response.body()).isEqualTo(wrongCode.body());
        }
        // the session is still the old one and still works
        assertThat(browser.get("/api/admin/sessions/current").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-008: senha com mais de 128 caracteres e campos ausentes dão 400 sem tocar no limitador")
    void shouldRefuseOversizedAndMissingFieldsWithoutTouchingTheLimiter() throws Exception {
        AdminBrowser browser = fullSession();
        String code = freshCode();

        for (int i = 0; i < 8; i++) {
            assertCredentialsInvalid(reauthenticate(browser, "x".repeat(129), code));
            assertCredentialsInvalid(browser.post(REAUTHENTICATION, "{\"code\":\"" + code + "\"}"));
            assertCredentialsInvalid(browser.post(REAUTHENTICATION, "{}"));
        }

        // 24 admitted failures would have locked the account several times over
        assertThat(reauthenticate(browser, PASSWORD, code).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-008: senha ou código em branco dão 400 sem gastar tentativa do limite")
    void shouldRefuseBlankFieldsWithoutTouchingTheLimiter() throws Exception {
        AdminBrowser browser = fullSession();
        String code = freshCode();

        for (int i = 0; i < 8; i++) {
            assertCredentialsInvalid(reauthenticate(browser, "", ""));
            assertCredentialsInvalid(reauthenticate(browser, "", code));
            assertCredentialsInvalid(reauthenticate(browser, PASSWORD, "   "));
        }

        // empty submits from the form must not lock the Administrator out of the login
        assertThat(reauthenticate(browser, PASSWORD, code).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-008: o código usado no login não vale na reautenticação")
    void shouldNotAcceptTheCodeOfTheLogin() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        clock.advance(Duration.ofSeconds(30));
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        String loginCode = AdminMfaTestSupport.codeAt(secret, clock.instant());
        assertThat(browser.verifyMfa(loginCode).statusCode()).isEqualTo(204);
        Long stepOfLogin = Totp.stepAt(clock.instant());
        assertThat(lastStep()).isEqualTo(stepOfLogin);

        assertCredentialsInvalid(reauthenticate(browser, PASSWORD, loginCode));

        assertThat(lastStep()).isEqualTo(stepOfLogin);
    }

    @Test
    @DisplayName("SEC-008: o código de uma reautenticação não vale numa segunda")
    void shouldNotAcceptTheCodeOfAPreviousReauthentication() throws Exception {
        AdminBrowser browser = fullSession();
        String code = freshCode();

        assertThat(reauthenticate(browser, PASSWORD, code).statusCode()).isEqualTo(204);

        assertCredentialsInvalid(reauthenticate(browser, PASSWORD, code));
    }

    @Test
    @DisplayName("SEC-008: duas reautenticações paralelas com o mesmo código dão um 204 e um 400")
    void shouldAcceptOnlyOneOfTwoParallelReauthenticationsWithTheSameCode() throws Exception {
        List<AdminBrowser> browsers = List.of(fullSession(), fullSession());
        String code = freshCode();
        long step = Totp.stepAt(clock.instant());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (AdminBrowser browser : browsers) {
                results.add(pool.submit(() -> {
                    start.await();
                    return reauthenticate(browser, PASSWORD, code).statusCode();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(204, 400);
        assertThat(lastStep()).isEqualTo(step);
    }

    @Test
    @DisplayName("SEC-008: código de recuperação não serve para reautenticar")
    void shouldNotAcceptARecoveryCode() throws Exception {
        List<String> codes = RecoveryCodes.generate();
        recoveryCodes.replaceAll(
                adminId, codes.stream().map(RecoveryCodes::hash).toList());
        AdminBrowser browser = fullSession();

        assertCredentialsInvalid(reauthenticate(browser, PASSWORD, codes.get(0)));

        assertThat(recoveryCodes.findUnused(adminId)).hasSize(10);
    }

    @Test
    @DisplayName("SEC-008: 5 senhas erradas bloqueiam a reautenticação e o login do painel com 429")
    void shouldLockReauthenticationAndPanelLoginAfterFiveWrongPasswords() throws Exception {
        AdminBrowser browser = fullSession();
        String code = freshCode();
        for (int i = 0; i < 5; i++) {
            assertCredentialsInvalid(reauthenticate(browser, WRONG_PASSWORD, code));
        }

        HttpResponse<String> locked = reauthenticate(browser, PASSWORD, code);
        HttpResponse<String> panelLogin = new AdminBrowser(port).loginAdmin(email, PASSWORD);

        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        assertThat(panelLogin.statusCode()).isEqualTo(429);
        assertThat(panelLogin.headers().firstValue("Retry-After")).isPresent();
        assertThat(reauthenticationAudit()).containsOnlyOnce("BLOCKED");
    }

    @Test
    @DisplayName("SEC-008: 5 códigos errados bloqueiam a reautenticação com 429")
    void shouldLockReauthenticationAfterFiveWrongCodes() throws Exception {
        AdminBrowser browser = fullSession();
        clock.advance(Duration.ofSeconds(30));
        String wrong = AdminMfaTestSupport.wrongCodeAt(secret, clock.instant());
        for (int i = 0; i < 5; i++) {
            assertCredentialsInvalid(reauthenticate(browser, PASSWORD, wrong));
        }
        String right = AdminMfaTestSupport.codeAt(secret, clock.instant());

        HttpResponse<String> locked = reauthenticate(browser, PASSWORD, right);

        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        // the code key is the login's: a pending session cannot get past the code step either
        AdminBrowser pending = new AdminBrowser(port);
        assertThat(pending.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        assertThat(pending.verifyMfa(right).statusCode()).isEqualTo(429);
        assertThat(reauthenticationAudit()).containsOnlyOnce("BLOCKED");
    }

    @Test
    @DisplayName("SEC-008: erros no login e na reautenticação somam no mesmo limite")
    void shouldCountLoginAndReauthenticationErrorsInTheSameLimit() throws Exception {
        AdminBrowser session = fullSession();
        String code = freshCode();
        AdminBrowser other = new AdminBrowser(port);
        for (int i = 0; i < 3; i++) {
            assertThat(other.loginAdmin(email, WRONG_PASSWORD).statusCode()).isEqualTo(401);
        }
        for (int i = 0; i < 2; i++) {
            assertCredentialsInvalid(reauthenticate(session, WRONG_PASSWORD, code));
        }

        HttpResponse<String> reauthentication = reauthenticate(session, PASSWORD, code);
        HttpResponse<String> panelLogin = new AdminBrowser(port).loginAdmin(email, PASSWORD);

        assertThat(reauthentication.statusCode()).isEqualTo(429);
        assertThat(reauthentication.headers().firstValue("Retry-After")).isPresent();
        assertThat(panelLogin.statusCode()).isEqualTo(429);
        assertThat(reauthenticationAudit()).contains("BLOCKED");
    }

    @Test
    @DisplayName("SEC-065: reautenticação certa, errada e bloqueada vão para a auditoria sem senha nem código")
    void shouldAuditEveryOutcomeWithoutThePasswordOrTheCode(CapturedOutput output) throws Exception {
        AdminBrowser browser = fullSession();
        String code = freshCode();
        assertCredentialsInvalid(reauthenticate(browser, WRONG_PASSWORD, code));
        clock.advance(Duration.ofSeconds(30));
        String wrongCode = AdminMfaTestSupport.wrongCodeAt(secret, clock.instant());
        assertCredentialsInvalid(reauthenticate(browser, PASSWORD, wrongCode));
        String rightCode = AdminMfaTestSupport.codeAt(secret, clock.instant());
        assertThat(reauthenticate(browser, PASSWORD, rightCode).statusCode()).isEqualTo(204);
        for (int i = 0; i < 5; i++) {
            reauthenticate(browser, WRONG_PASSWORD, rightCode);
        }
        assertThat(reauthenticate(browser, PASSWORD, rightCode).statusCode()).isEqualTo(429);

        assertThat(reauthenticationAudit()).contains("FAILURE", "SUCCESS", "BLOCKED");
        String audit = jdbc.queryForObject(
                "select coalesce(string_agg(t::text, ' '), '') from audit_events t where actor_id = ?",
                String.class,
                adminId);
        for (String forbidden : List.of(PASSWORD, WRONG_PASSWORD, email)) {
            assertThat(audit).doesNotContain(forbidden);
            assertThat(output.getAll()).doesNotContain(forbidden);
        }
        // a 6-digit code is looked for as a whole number, so a timestamp or an id cannot match by chance
        for (String typed : List.of(code, wrongCode, rightCode)) {
            Pattern whole = Pattern.compile("(?<![0-9])" + typed + "(?![0-9])");
            assertThat(whole.matcher(audit).find())
                    .as("audit carries %s", typed)
                    .isFalse();
            assertThat(whole.matcher(output.getAll()).find())
                    .as("log carries %s", typed)
                    .isFalse();
        }
    }
}
