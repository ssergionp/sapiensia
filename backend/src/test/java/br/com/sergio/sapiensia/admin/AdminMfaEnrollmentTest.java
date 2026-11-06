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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bouncycastle.util.encoders.Base32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Enrolling the authenticator: only while MFA is inactive, and only the first confirmed code activates it. */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-mfa-enrollment", "app.login-limit.ip-max-attempts=1000"})
class AdminMfaEnrollmentTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow
    private static final String CURRENT = "/api/admin/sessions/current";
    private static final String MFA = "/api/admin/sessions/mfa";
    private static final String ENROLLMENT = MFA + "/enrollment";
    private static final String CONFIRMATION = ENROLLMENT + "/confirmation";
    private static final Pattern SECRET = Pattern.compile("\"secret\":\"([A-Z2-7]{32})\"");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SecretCipher cipher;

    @Autowired
    Administrators administrators;

    @Autowired
    AdminMfaTestSupport.MutableClock clock;

    private String email;
    private UUID adminId;

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
        // every test starts on a step no other test used
        clock.advance(Duration.ofMinutes(1));
    }

    private AdminBrowser pending() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        return browser;
    }

    private static byte[] secretOf(HttpResponse<String> enrollment) {
        Matcher m = SECRET.matcher(enrollment.body());
        assertThat(m.find()).as("enrollment body carries a Base32 secret").isTrue();
        return Base32.decode(m.group(1));
    }

    private String code(byte[] secret) {
        return AdminMfaTestSupport.codeAt(secret, clock.instant());
    }

    private static String body(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    private String storedSecret() {
        return jdbc.queryForObject("select totp_secret from administrators where id = ?", String.class, adminId);
    }

    @Test
    @DisplayName("SEC-001: sem MFA, GET informa enrolled=false")
    void shouldReportNotEnrolled() throws Exception {
        HttpResponse<String> response = pending().get(MFA);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"enrolled\":false}");
    }

    @Test
    @DisplayName("SEC-001: a inscrição devolve o segredo e a URI, sem cache, e grava só o texto cifrado")
    void shouldStartEnrollmentWithoutActivatingIt() throws Exception {
        HttpResponse<String> response = pending().post(ENROLLMENT, "{}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        Matcher m = SECRET.matcher(response.body());
        assertThat(m.find()).isTrue();
        String base32 = m.group(1);
        assertThat(response.body())
                .contains("\"otpauthUri\":\"otpauth://totp/SapiensIA:"
                        + email.replace("@", "%40")
                        + "?secret="
                        + base32
                        + "&issuer=SapiensIA&algorithm=SHA1&digits=6&period=30\"");
        String stored = storedSecret();
        assertThat(stored).startsWith("v1:").doesNotContain(base32);
        assertThat(jdbc.queryForObject(
                        "select totp_enabled_at is null from administrators where id = ?", Boolean.class, adminId))
                .isTrue();
        assertThat(pending().get(MFA).body()).isEqualTo("{\"enrolled\":false}");
    }

    @Test
    @DisplayName("SEC-001: uma segunda inscrição troca o segredo; só o código do segundo confirma")
    void shouldReplaceThePendingSecretOnASecondEnrollment() throws Exception {
        AdminBrowser browser = pending();
        byte[] first = secretOf(browser.post(ENROLLMENT, "{}"));
        byte[] second = secretOf(browser.post(ENROLLMENT, "{}"));
        assertThat(second).isNotEqualTo(first);

        assertThat(browser.post(CONFIRMATION, body(code(first))).statusCode()).isEqualTo(400);
        assertThat(browser.post(CONFIRMATION, body(code(second))).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-001: código errado na confirmação dá 400 e nada é ativado")
    void shouldNotActivateWithAWrongCode() throws Exception {
        AdminBrowser browser = pending();
        byte[] secret = secretOf(browser.post(ENROLLMENT, "{}"));

        HttpResponse<String> response =
                browser.post(CONFIRMATION, body(AdminMfaTestSupport.wrongCodeAt(secret, clock.instant())));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"errors\":{\"code\":\"CODE_INVALID\"}");
        assertThat(jdbc.queryForObject(
                        "select totp_enabled_at is null from administrators where id = ?", Boolean.class, adminId))
                .isTrue();
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-001: o código certo ativa o MFA, abre a sessão e não vale de novo no login")
    void shouldActivateWithTheRightCode() throws Exception {
        AdminBrowser browser = pending();
        String pendingSession = browser.cookie("JSESSIONID");
        String pendingXsrf = browser.cookie("XSRF-TOKEN");
        byte[] secret = secretOf(browser.post(ENROLLMENT, "{}"));
        String code = code(secret);

        HttpResponse<String> response = browser.post(CONFIRMATION, body(code));

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(browser.cookie("JSESSIONID")).isNotBlank().isNotEqualTo(pendingSession);
        assertThat(browser.cookie("XSRF-TOKEN")).isNotBlank().isNotEqualTo(pendingXsrf);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "select totp_enabled_at is not null from administrators where id = ?", Boolean.class, adminId))
                .isTrue();
        assertThat(jdbc.queryForObject("select totp_last_step from administrators where id = ?", Long.class, adminId))
                .isEqualTo(Totp.stepAt(clock.instant()));
        AdminBrowser again = pending();
        assertThat(again.verifyMfa(code).statusCode()).isEqualTo(400);
        assertThat(again.get(CURRENT).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-001: com o MFA ativo a inscrição e a confirmação dão 409 e o segredo não muda")
    void shouldRefuseEnrollmentWhileMfaIsActive() throws Exception {
        byte[] oldSecret = AdminMfaTestSupport.enableMfa(jdbc, cipher, adminId);
        String before = storedSecret();
        AdminBrowser browser = pending();

        HttpResponse<String> enrollment = browser.post(ENROLLMENT, "{}");
        HttpResponse<String> confirmation = browser.post(CONFIRMATION, body(code(oldSecret)));
        HttpResponse<String> status = browser.get(MFA);

        assertThat(enrollment.statusCode()).isEqualTo(409);
        assertThat(confirmation.statusCode()).isEqualTo(409);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body()).isEqualTo("{\"enrolled\":true}");
        assertThat(storedSecret()).isEqualTo(before);
        String base32 = Totp.toBase32(oldSecret);
        for (HttpResponse<String> r : List.of(enrollment, confirmation, status)) {
            assertThat(r.body()).doesNotContain(base32).doesNotContain("otpauth");
            assertThat(r.headers().map().toString()).doesNotContain(base32);
        }
        // the old authenticator still gets in, in the same pending session
        assertThat(browser.verifyMfa(code(oldSecret)).statusCode()).isEqualTo(204);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-001: um segredo pendente nunca autentica: o código dele no login dá 409")
    void shouldNeverAuthenticateWithAPendingSecret() throws Exception {
        AdminBrowser browser = pending();
        byte[] secret = secretOf(browser.post(ENROLLMENT, "{}"));

        HttpResponse<String> response = browser.verifyMfa(code(secret));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-001: confirmar sem inscrição começada dá 409")
    void shouldRefuseConfirmationWithoutEnrollment() throws Exception {
        HttpResponse<String> response = pending().post(CONFIRMATION, body("123456"));

        assertThat(response.statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("SEC-001: as rotas POST do MFA sem o token CSRF dão 403 e nada muda")
    void shouldRefuseTheMfaPostRoutesWithoutTheCsrfToken() throws Exception {
        AdminBrowser browser = pending();
        byte[] secret = secretOf(browser.post(ENROLLMENT, "{}"));
        String before = storedSecret();
        String code = code(secret);

        assertThat(browser.postWithoutCsrf(ENROLLMENT, "{}").statusCode()).isEqualTo(403);
        assertThat(storedSecret()).isEqualTo(before);
        assertThat(browser.postWithoutCsrf(CONFIRMATION, body(code)).statusCode())
                .isEqualTo(403);
        assertThat(jdbc.queryForObject(
                        "select totp_enabled_at is null from administrators where id = ?", Boolean.class, adminId))
                .isTrue();
        assertThat(browser.postWithoutCsrf(MFA, body(code)).statusCode()).isEqualTo(403);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
        // the same session and the same code still work with the token: nothing was spent
        assertThat(browser.post(CONFIRMATION, body(code)).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-001: startEnrollment e enable são UPDATEs condicionais ao MFA inativo")
    void shouldRefuseAtSqlLevelWhileMfaIsActive() {
        AdminMfaTestSupport.enableMfa(jdbc, cipher, adminId);
        String before = storedSecret();

        assertThat(administrators.startEnrollment(adminId, "v1:outro")).isZero();
        assertThat(administrators.enable(adminId, before, 1_000_000)).isZero();
        assertThat(storedSecret()).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-001: enable só vale para o segredo que foi confirmado e uma vez só")
    void shouldEnableOnlyTheConfirmedSecretOnce() {
        assertThat(administrators.startEnrollment(adminId, "v1:primeiro")).isEqualTo(1);

        assertThat(administrators.enable(adminId, "v1:outro", 7)).isZero();
        assertThat(administrators.enable(adminId, "v1:primeiro", 7)).isEqualTo(1);
        assertThat(administrators.enable(adminId, "v1:primeiro", 7)).isZero();
        assertThat(administrators.startEnrollment(adminId, "v1:terceiro")).isZero();
    }

    @Test
    @DisplayName("SEC-001: duas confirmações paralelas com o mesmo código: um 204 e a outra recusada")
    void shouldActivateOnlyOnceWithParallelConfirmations() throws Exception {
        AdminBrowser first = pending();
        AdminBrowser second = pending();
        byte[] secret = secretOf(first.post(ENROLLMENT, "{}"));
        String code = code(secret);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (AdminBrowser browser : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return browser.post(CONFIRMATION, body(code)).statusCode();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).filteredOn(s -> s == 204).hasSize(1);
        assertThat(statuses).filteredOn(s -> s != 204).hasSize(1).allMatch(s -> s == 409 || s == 400);
    }

    @Test
    @DisplayName("SEC-002: 5 códigos errados na confirmação dão 429 com Retry-After, e o contador é o do login")
    void shouldLockTheConfirmationAndShareTheCounterWithTheLogin() throws Exception {
        AdminBrowser browser = pending();
        byte[] secret = secretOf(browser.post(ENROLLMENT, "{}"));
        for (int i = 0; i < 5; i++) {
            assertThat(browser.post(CONFIRMATION, body(AdminMfaTestSupport.wrongCodeAt(secret, clock.instant())))
                            .statusCode())
                    .isEqualTo(400);
        }

        HttpResponse<String> locked = browser.post(CONFIRMATION, body(code(secret)));

        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        // the same account key: once MFA is active, the login code is locked by those failures
        byte[] active = AdminMfaTestSupport.enableMfa(jdbc, cipher, adminId);
        assertThat(browser.verifyMfa(code(active)).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("SEC-002: erros de código do login contam contra a confirmação")
    void shouldCountLoginFailuresAgainstTheConfirmation() throws Exception {
        byte[] active = AdminMfaTestSupport.enableMfa(jdbc, cipher, adminId);
        AdminBrowser browser = pending();
        for (int i = 0; i < 5; i++) {
            assertThat(browser.verifyMfa(AdminMfaTestSupport.wrongCodeAt(active, clock.instant()))
                            .statusCode())
                    .isEqualTo(400);
        }
        // the account is locked for codes; the enrollment is refused with 409 (active) before spending anything,
        // but a pending secret would not escape the lock: reopen the enrollment and try
        jdbc.update("update administrators set totp_enabled_at = null where id = ?", adminId);

        assertThat(browser.post(CONFIRMATION, body(code(active))).statusCode()).isEqualTo(429);
    }
}
