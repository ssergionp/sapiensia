package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.email.EmailMessage;
import br.com.sergio.sapiensia.security.Tokens;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
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

/**
 * #73: the Administrator replaces the TOTP authenticator. The start asks for the current code (or none in a session
 * that began with a recovery code), the confirmation for a code of the new secret; the swap, the new recovery codes and
 * the end of the other sessions commit together.
 */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-authenticator-replacement", "app.login-limit.ip-max-attempts=1000"})
class AdminAuthenticatorReplacementTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow
    private static final String CURRENT = "/api/admin/sessions/current";
    private static final String MFA = "/api/admin/sessions/mfa";
    private static final String ENROLLMENT = MFA + "/enrollment";
    private static final String ENROLLMENT_CONFIRMATION = ENROLLMENT + "/confirmation";
    private static final String RECOVERY = MFA + "/recovery-code";
    private static final String REAUTHENTICATION = "/api/admin/sessions/reauthentication";
    private static final String START = "/api/admin/authenticator-replacement";
    private static final String CONFIRM = START + "/confirmation";
    private static final Pattern SECRET = Pattern.compile("\"secret\":\"([A-Z2-7]{32})\"");
    private static final Pattern URI = Pattern.compile("\"otpauthUri\":\"(otpauth://totp/[^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("\"([A-Z2-7]{24})\"");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    RecordingEmailSender sender;

    @Autowired
    AdminMfaTestSupport.MutableClock clock;

    private String email;
    private UUID adminId;

    record Activated(byte[] secret, List<String> codes) {}

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
        clock.advance(Duration.ofMinutes(1));
    }

    private static String body(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    private String totp(byte[] secret) {
        return AdminMfaTestSupport.codeAt(secret, clock.instant());
    }

    private AdminBrowser pending() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        return browser;
    }

    private static byte[] secretOf(HttpResponse<String> response) {
        Matcher m = SECRET.matcher(response.body());
        assertThat(m.find()).as("body carries a Base32 secret").isTrue();
        return Base32.decode(m.group(1));
    }

    private static List<String> codesOf(HttpResponse<String> response) {
        List<String> codes = new ArrayList<>();
        Matcher m = CODE.matcher(response.body());
        while (m.find()) {
            codes.add(m.group(1));
        }
        assertThat(codes).hasSize(10);
        return codes;
    }

    /** The real enrollment: the TOTP secret and the 10 recovery codes. */
    private Activated activate() throws Exception {
        AdminBrowser browser = pending();
        byte[] secret = secretOf(browser.post(ENROLLMENT, "{}"));
        HttpResponse<String> confirmation = browser.post(ENROLLMENT_CONFIRMATION, body(totp(secret)));
        assertThat(confirmation.statusCode()).isEqualTo(200);
        return new Activated(secret, codesOf(confirmation));
    }

    /** A full session by TOTP (the clock moves one step first, and one more after, for the next code). */
    private AdminBrowser totpSession(byte[] secret) throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginAdminFully(email, PASSWORD, secret, clock).statusCode())
                .isEqualTo(204);
        clock.advance(Duration.ofSeconds(30));
        return browser;
    }

    private AdminBrowser recoverySession(String recoveryCode) throws Exception {
        AdminBrowser browser = pending();
        assertThat(browser.post(RECOVERY, body(recoveryCode)).statusCode()).isEqualTo(204);
        return browser;
    }

    private static String correlationId(HttpResponse<String> response) {
        return response.headers().firstValue("X-Correlation-Id").orElseThrow();
    }

    private List<String> auditOf(String correlationId) {
        return jdbc.queryForList(
                "select action || ' ' || result from audit_events where actor_id = ? and correlation_id = ?::uuid",
                String.class,
                adminId,
                correlationId);
    }

    private long replacedAudits() {
        return jdbc.queryForObject(
                "select count(*) from audit_events where actor_id = ? and action = 'ADMIN_MFA_REPLACED'",
                Long.class,
                adminId);
    }

    private String storedSecret() {
        return jdbc.queryForObject("select totp_secret from administrators where id = ?", String.class, adminId);
    }

    private List<String> unusedHashes() {
        return jdbc.queryForList(
                "select code_hash from administrator_recovery_codes where administrator_id = ? and used_at is null",
                String.class,
                adminId);
    }

    /** The answer to the code of a password + code login with {@code secret} (the clock moves one step first). */
    private int loginStatus(byte[] secret) throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        clock.advance(Duration.ofSeconds(30));
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        return browser.verifyMfa(totp(secret)).statusCode();
    }

    @Test
    @DisplayName("SEC-006 SEC-008: a sessão TOTP começa a troca com o código atual e recebe segredo e URI sem cache")
    void shouldStartWithTheCurrentCodeAndReturnSecretAndUriWithoutCache() throws Exception {
        Activated activated = activate();
        AdminBrowser full = totpSession(activated.secret());
        String before = storedSecret();

        HttpResponse<String> response = full.post(START, body(totp(activated.secret())));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        byte[] fresh = secretOf(response);
        assertThat(fresh).isNotEqualTo(activated.secret());
        Matcher uri = URI.matcher(response.body());
        assertThat(uri.find()).isTrue();
        assertThat(uri.group(1))
                .contains("secret=" + Base32.toBase32String(fresh).replace("=", ""));
        // nothing stored yet, nothing audited (D1, D10)
        assertThat(storedSecret()).isEqualTo(before);
        assertThat(auditOf(correlationId(response))).isEmpty();
    }

    @Test
    @DisplayName("SEC-008: a sessão TOTP não começa a troca sem código (400, audita FAILURE)")
    void shouldNotStartWithoutCode() throws Exception {
        AdminBrowser full = totpSession(activate().secret());

        HttpResponse<String> missing = full.post(START, "{}");

        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body())
                .contains("\"errors\":{\"code\":\"CODE_INVALID\"}")
                .doesNotContain("secret");
        assertThat(auditOf(correlationId(missing))).containsExactly("ADMIN_MFA_REPLACED FAILURE");
    }

    @Test
    @DisplayName(
            "SEC-008: a sessão TOTP não começa a troca com código errado (audita ADMIN_MFA_REPLACED FAILURE, sem segredo)")
    void shouldNotStartWithWrongCode() throws Exception {
        Activated activated = activate();
        AdminBrowser full = totpSession(activated.secret());

        HttpResponse<String> wrong =
                full.post(START, body(AdminMfaTestSupport.wrongCodeAt(activated.secret(), clock.instant())));

        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(wrong.body()).doesNotContain("secret").doesNotContain("otpauth");
        assertThat(auditOf(correlationId(wrong))).containsExactly("ADMIN_MFA_REPLACED FAILURE");
    }

    @Test
    @DisplayName("SEC-008 SEC-006: o código usado no login não começa a troca (replay)")
    void shouldRefuseTheCodeOfTheLoginToStart() throws Exception {
        Activated activated = activate();
        clock.advance(Duration.ofSeconds(30));
        AdminBrowser browser = pending();
        String code = totp(activated.secret());
        assertThat(browser.verifyMfa(code).statusCode()).isEqualTo(204);

        HttpResponse<String> replay = browser.post(START, body(code));

        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(replay.body()).doesNotContain("secret");
        assertThat(auditOf(correlationId(replay))).containsExactly("ADMIN_MFA_REPLACED FAILURE");
    }

    @Test
    @DisplayName("SEC-008 SEC-006: o mesmo passo não serve duas vezes")
    void shouldNotAcceptTheSameStepTwice() throws Exception {
        Activated activated = activate();
        AdminBrowser full = totpSession(activated.secret());
        String code = totp(activated.secret());

        assertThat(full.post(START, body(code)).statusCode()).isEqualTo(200);
        assertThat(full.post(START, body(code)).statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("SEC-006: depois do limite de falhas o início responde 429 com Retry-After e audita BLOCKED")
    void shouldLockAfterTheFailureLimit() throws Exception {
        Activated activated = activate();
        AdminBrowser full = totpSession(activated.secret());
        for (int i = 0; i < 5; i++) {
            assertThat(full.post(START, body(AdminMfaTestSupport.wrongCodeAt(activated.secret(), clock.instant())))
                            .statusCode())
                    .isEqualTo(400);
        }

        HttpResponse<String> locked = full.post(START, body(totp(activated.secret())));

        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        assertThat(locked.body()).doesNotContain("secret");
        assertThat(auditOf(correlationId(locked))).containsExactly("ADMIN_MFA_REPLACED BLOCKED");
    }

    @Test
    @DisplayName("SEC-006: as falhas do login MFA e as do início da troca somam no mesmo limite (3 + 2 dão 429)")
    void shouldShareTheFailureLimitWithTheMfaLogin() throws Exception {
        Activated activated = activate();
        AdminBrowser full = totpSession(activated.secret());
        AdminBrowser login = pending();
        for (int i = 0; i < 3; i++) {
            assertThat(login.verifyMfa(AdminMfaTestSupport.wrongCodeAt(activated.secret(), clock.instant()))
                            .statusCode())
                    .isEqualTo(400);
        }
        for (int i = 0; i < 2; i++) {
            assertThat(full.post(START, body(AdminMfaTestSupport.wrongCodeAt(activated.secret(), clock.instant())))
                            .statusCode())
                    .isEqualTo(400);
        }

        HttpResponse<String> locked = full.post(START, body(totp(activated.secret())));

        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        assertThat(auditOf(correlationId(locked))).containsExactly("ADMIN_MFA_REPLACED BLOCKED");
    }

    @Test
    @DisplayName(
            "SEC-001: um segundo início na mesma sessão substitui o segredo pendente; o código do primeiro é recusado")
    void shouldReplaceThePendingSecretOnASecondStart() throws Exception {
        Activated activated = activate();
        AdminBrowser recovered = recoverySession(activated.codes().getFirst());
        byte[] first = secretOf(recovered.post(START, "{}"));
        byte[] second = secretOf(recovered.post(START, "{}"));
        String before = storedSecret();

        HttpResponse<String> stale = recovered.post(CONFIRM, body(totp(first)));

        assertThat(stale.statusCode()).isEqualTo(400);
        assertThat(storedSecret()).isEqualTo(before);
        assertThat(recovered.post(CONFIRM, body(totp(second))).statusCode()).isEqualTo(200);
        assertThat(loginStatus(second)).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-065 SEC-006: a sessão que entrou com código de recuperação começa a troca sem código antigo")
    void shouldStartWithoutTheOldCodeInARecoverySession() throws Exception {
        Activated activated = activate();
        AdminBrowser recovered = recoverySession(activated.codes().getFirst());
        assertThat(recovered.get(CURRENT).body()).contains("\"oldCodeRequired\":false");
        String before = storedSecret();

        HttpResponse<String> response = recovered.post(START, "{}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(secretOf(response)).isNotEqualTo(activated.secret());
        assertThat(storedSecret()).isEqualTo(before);
        // any code typed is ignored (D8)
        assertThat(recovered.post(START, body("000000")).statusCode()).isEqualTo(200);
        // and the TOTP session asks for the old code
        assertThat(totpSession(activated.secret()).get(CURRENT).body()).contains("\"oldCodeRequired\":true");
    }

    @Test
    @DisplayName(
            "SEC-006: depois de reautenticar por TOTP, a sessão que veio de recuperação volta a exigir o código antigo")
    void shouldAskForTheOldCodeAgainAfterReauthentication() throws Exception {
        Activated activated = activate();
        AdminBrowser recovered = recoverySession(activated.codes().getFirst());
        clock.advance(Duration.ofSeconds(30));
        HttpResponse<String> reauthentication = recovered.post(
                REAUTHENTICATION, "{\"password\":\"" + PASSWORD + "\",\"code\":\"" + totp(activated.secret()) + "\"}");
        assertThat(reauthentication.statusCode()).isEqualTo(204);

        assertThat(recovered.get(CURRENT).body()).contains("\"oldCodeRequired\":true");
        HttpResponse<String> start = recovered.post(START, "{}");
        assertThat(start.statusCode()).isEqualTo(400);
        assertThat(start.body()).doesNotContain("secret");
    }

    @Test
    @DisplayName(
            "SEC-008 SEC-006: fora da janela de autenticação recente, a sessão que veio de recuperação volta a exigir o código atual")
    void shouldAskForTheCurrentCodeOutsideTheRecentAuthenticationWindow() throws Exception {
        Activated activated = activate();
        AdminBrowser recovered = recoverySession(activated.codes().getFirst());
        clock.advance(Duration.ofMinutes(4));
        // inside the window (5 min) the shortcut still holds
        assertThat(recovered.get(CURRENT).body()).contains("\"oldCodeRequired\":false");

        clock.advance(Duration.ofMinutes(2));

        assertThat(recovered.get(CURRENT).body()).contains("\"oldCodeRequired\":true");
        HttpResponse<String> start = recovered.post(START, "{}");
        assertThat(start.statusCode()).isEqualTo(400);
        assertThat(start.body()).doesNotContain("secret");
        assertThat(auditOf(correlationId(start))).containsExactly("ADMIN_MFA_REPLACED FAILURE");
        // with the current code the same session starts
        assertThat(recovered.post(START, body(totp(activated.secret()))).statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-001: antes da confirmação o segredo antigo continua entrando e o novo é recusado no login")
    void shouldKeepTheOldSecretUntilTheConfirmation() throws Exception {
        Activated activated = activate();
        AdminBrowser recovered = recoverySession(activated.codes().getFirst());
        byte[] fresh = secretOf(recovered.post(START, "{}"));

        assertThat(loginStatus(fresh)).isEqualTo(400);
        assertThat(loginStatus(activated.secret())).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-001: a confirmação sem troca iniciada na sessão responde 409 e não altera o segredo")
    void shouldAnswerConflictWhenNoReplacementWasStarted() throws Exception {
        Activated activated = activate();
        AdminBrowser full = totpSession(activated.secret());
        String before = storedSecret();

        HttpResponse<String> response = full.post(CONFIRM, body(totp(activated.secret())));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(storedSecret()).isEqualTo(before);
        assertThat(replacedAudits()).isZero();
    }

    @Test
    @DisplayName("SEC-008: código errado do segredo novo é recusado, audita FAILURE e mantém o segredo antigo")
    void shouldRefuseAWrongCodeOfTheNewSecret() throws Exception {
        Activated activated = activate();
        AdminBrowser recovered = recoverySession(activated.codes().getFirst());
        byte[] fresh = secretOf(recovered.post(START, "{}"));
        String before = storedSecret();
        List<String> codesBefore = unusedHashes();

        HttpResponse<String> wrong =
                recovered.post(CONFIRM, body(AdminMfaTestSupport.wrongCodeAt(fresh, clock.instant())));

        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(auditOf(correlationId(wrong))).containsExactly("ADMIN_MFA_REPLACED FAILURE");
        assertThat(storedSecret()).isEqualTo(before);
        assertThat(unusedHashes()).containsExactlyInAnyOrderElementsOf(codesBefore);
        // the pending secret survives a typo: the right code still confirms
        assertThat(recovered.post(CONFIRM, body(totp(fresh))).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-001 SEC-006: confirmada a troca, o segredo antigo é recusado e o novo entra")
    void shouldSwapTheSecretOnConfirmation() throws Exception {
        Activated activated = activate();
        AdminBrowser full = totpSession(activated.secret());
        byte[] fresh = secretOf(full.post(START, body(totp(activated.secret()))));
        String before = storedSecret();

        HttpResponse<String> confirmation = full.post(CONFIRM, body(totp(fresh)));

        assertThat(confirmation.statusCode()).isEqualTo(200);
        assertThat(confirmation.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(storedSecret()).isNotEqualTo(before);
        assertThat(loginStatus(activated.secret())).isEqualTo(400);
        assertThat(loginStatus(fresh)).isEqualTo(204);
    }

    @Test
    @DisplayName(
            "SEC-006: confirmada a troca, a outra sessão do mesmo Administrador cai (401) e a atual segue com novo id e novo CSRF")
    void shouldEndTheOtherSessionsAndRenewTheCurrentOne() throws Exception {
        Activated activated = activate();
        AdminBrowser full = totpSession(activated.secret());
        AdminBrowser other = totpSession(activated.secret());
        assertThat(other.get(CURRENT).statusCode()).isEqualTo(200);
        byte[] fresh = secretOf(full.post(START, body(totp(activated.secret()))));
        String sessionBefore = full.cookie("JSESSIONID");
        String csrfBefore = full.cookie("XSRF-TOKEN");

        assertThat(full.post(CONFIRM, body(totp(fresh))).statusCode()).isEqualTo(200);

        assertThat(other.get(CURRENT).statusCode()).isEqualTo(401);
        assertThat(full.get(CURRENT).statusCode()).isEqualTo(200);
        assertThat(full.get(CURRENT).body()).contains("\"oldCodeRequired\":true");
        assertThat(full.cookie("JSESSIONID")).isNotEqualTo(sessionBefore);
        assertThat(full.cookie("XSRF-TOKEN")).isNotEqualTo(csrfBefore);
        // the pending secret died with the old session: a second confirmation has nothing to confirm
        assertThat(full.post(CONFIRM, body(totp(fresh))).statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName(
            "SEC-065: confirmada a troca, os códigos de recuperação antigos são recusados e os 10 novos funcionam uma vez")
    void shouldReplaceTheRecoveryCodes() throws Exception {
        Activated activated = activate();
        AdminBrowser recovered = recoverySession(activated.codes().getFirst());
        byte[] fresh = secretOf(recovered.post(START, "{}"));

        HttpResponse<String> confirmation = recovered.post(CONFIRM, body(totp(fresh)));

        assertThat(confirmation.statusCode()).isEqualTo(200);
        // the recovery marker does not survive the new session: the old code is required again
        assertThat(recovered.get(CURRENT).body()).contains("\"oldCodeRequired\":true");
        HttpResponse<String> startAgain = recovered.post(START, "{}");
        assertThat(startAgain.statusCode()).isEqualTo(400);
        assertThat(startAgain.body()).doesNotContain("secret");
        List<String> renewed = codesOf(confirmation);
        assertThat(renewed).doesNotContainAnyElementsOf(activated.codes());
        assertThat(unusedHashes())
                .containsExactlyInAnyOrderElementsOf(
                        renewed.stream().map(Tokens::hash).toList());
        assertThat(pending().post(RECOVERY, body(activated.codes().get(1))).statusCode())
                .isEqualTo(400);
        assertThat(pending().post(RECOVERY, body(renewed.getFirst())).statusCode())
                .isEqualTo(204);
        assertThat(pending().post(RECOVERY, body(renewed.getFirst())).statusCode())
                .isEqualTo(400);
        assertThat(pending().post(RECOVERY, body(renewed.get(1))).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-008: duas confirmações simultâneas trocam uma vez só")
    void shouldSwapOnlyOnceOnConcurrentConfirmations() throws Exception {
        Activated activated = activate();
        AdminBrowser recovered = recoverySession(activated.codes().getFirst());
        byte[] fresh = secretOf(recovered.post(START, "{}"));
        String code = totp(fresh);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return recovered.post(CONFIRM, body(code)).statusCode();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
            assertThat(statuses).filteredOn(s -> s != 200).allMatch(s -> s == 409 || s == 401);
        } finally {
            pool.shutdownNow();
        }
        assertThat(unusedHashes()).hasSize(10);
        assertThat(replacedAudits()).isEqualTo(1);
        assertThat(loginStatus(fresh)).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-065: a auditoria tem ADMIN_MFA_REPLACED SUCCESS e nenhum segredo, código ou URI")
    void shouldAuditTheReplacementWithoutSecrets() throws Exception {
        Activated activated = activate();
        AdminBrowser full = totpSession(activated.secret());
        String oldCode = totp(activated.secret());
        HttpResponse<String> start = full.post(START, body(oldCode));
        byte[] fresh = secretOf(start);
        String newCode = totp(fresh);

        HttpResponse<String> confirmation = full.post(CONFIRM, body(newCode));

        assertThat(confirmation.statusCode()).isEqualTo(200);
        assertThat(auditOf(correlationId(confirmation))).containsExactly("ADMIN_MFA_REPLACED SUCCESS");
        assertThat(jdbc.queryForMap(
                        "select object_type, object_id from audit_events where correlation_id = ?::uuid",
                        correlationId(confirmation)))
                .containsEntry("object_type", "ADMINISTRATOR")
                .containsEntry("object_id", adminId);
        String trail = jdbc.queryForObject(
                "select coalesce(string_agg(t::text, ' '), '') from audit_events t where actor_id = ?",
                String.class,
                adminId);
        Matcher uri = URI.matcher(start.body());
        assertThat(uri.find()).isTrue();
        List<String> forbidden = new ArrayList<>(List.of(oldCode, newCode, uri.group(1)));
        forbidden.add(Base32.toBase32String(fresh).replace("=", ""));
        forbidden.addAll(codesOf(confirmation));
        for (String value : forbidden) {
            assertThat(trail).doesNotContain(value);
        }
    }

    @Test
    @DisplayName("SEC-065 SEC-006: a troca confirmada agenda exatamente um aviso por e-mail ao Administrador")
    void shouldEmailExactlyOneNoticeWhenTheReplacementIsConfirmed() throws Exception {
        Activated activated = activate();
        AdminBrowser full = totpSession(activated.secret());
        HttpResponse<String> start = full.post(START, body(totp(activated.secret())));
        byte[] fresh = secretOf(start);

        HttpResponse<String> confirmation = full.post(CONFIRM, body(totp(fresh)));

        assertThat(confirmation.statusCode()).isEqualTo(200);
        EmailMessage mail = sender.awaitMessageTo(email, Duration.ofSeconds(15)).orElseThrow();
        assertThat(mail.subject()).isEqualTo("O autenticador do painel foi trocado");
        assertThat(mail.text())
                .contains("O autenticador do painel da SapiensIA foi trocado")
                .doesNotContain("otpauth")
                .doesNotContain(Base32.toBase32String(fresh).replace("=", ""))
                .doesNotContain(Base32.toBase32String(activated.secret()).replace("=", ""));
        Matcher uri = URI.matcher(start.body());
        assertThat(uri.find()).isTrue();
        assertThat(mail.text()).doesNotContain(uri.group(1));
        for (String code : codesOf(confirmation)) {
            assertThat(mail.text()).doesNotContain(code);
        }
        Thread.sleep(2000); // a second notice, if any, would arrive by now
        assertThat(sender.messagesTo(email)).hasSize(1);
        assertThat(noticeTasks()).isZero();
    }

    @Test
    @DisplayName("SEC-065 SEC-006: a troca recusada (código errado) ou abandonada (só o início) não agenda aviso")
    void shouldNotEmailWhenTheReplacementIsRefusedOrOnlyStarted() throws Exception {
        Activated activated = activate();
        AdminBrowser recovered = recoverySession(activated.codes().getFirst());
        byte[] fresh = secretOf(recovered.post(START, "{}"));

        HttpResponse<String> wrong =
                recovered.post(CONFIRM, body(AdminMfaTestSupport.wrongCodeAt(fresh, clock.instant())));

        assertThat(wrong.statusCode()).isEqualTo(400);
        Thread.sleep(2500);
        assertThat(sender.attemptsTo(email)).isZero();
        assertThat(noticeTasks()).isZero();
    }

    private Integer noticeTasks() {
        return jdbc.queryForObject(
                "select count(*) from scheduled_tasks where task_name = ?",
                Integer.class,
                AdministratorEmailTasks.AUTHENTICATOR_REPLACED_NOTICE);
    }

    @Test
    @DisplayName("SEC-018: o principal tem 6 argumentos com viaRecoveryCode falso e o toString não mostra o e-mail")
    void shouldDefaultViaRecoveryCodeToFalseAndHideTheEmail() {
        var principal = new AdministratorPrincipal(
                adminId, email, 0, EnumSet.allOf(Capability.class), Instant.now(), Instant.now());

        assertThat(principal.viaRecoveryCode()).isFalse();
        assertThat(principal.toString()).doesNotContain(email);
    }
}
