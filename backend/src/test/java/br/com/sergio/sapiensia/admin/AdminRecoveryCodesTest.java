package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.Tokens;
import br.com.sergio.sapiensia.security.Totp;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0004 / SEC-065: the recovery codes shown once at the MFA activation, their one-time use, the shared limiter and
 * the audit trail of the actions (activation, TOTP login, recovery code login, regeneration).
 */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-recovery-codes", "app.login-limit.ip-max-attempts=1000"})
class AdminRecoveryCodesTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow
    private static final String CURRENT = "/api/admin/sessions/current";
    private static final String MFA = "/api/admin/sessions/mfa";
    private static final String ENROLLMENT = MFA + "/enrollment";
    private static final String CONFIRMATION = ENROLLMENT + "/confirmation";
    private static final String RECOVERY = MFA + "/recovery-code";
    private static final String REGENERATE = "/api/admin/recovery-codes";
    private static final Pattern SECRET = Pattern.compile("\"secret\":\"([A-Z2-7]{32})\"");
    private static final Pattern CODES =
            Pattern.compile("^\\{\"codes\":\\[(\"[A-Z2-7]{24}\"(,\"[A-Z2-7]{24}\"){9})]}$");
    private static final Pattern CODE = Pattern.compile("\"([A-Z2-7]{24})\"");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AdminMfaTestSupport.MutableClock clock;

    @Autowired
    PlatformTransactionManager transactionManager;

    private String email;
    private UUID adminId;

    /** What the activation gave: the authenticator secret and the 10 codes shown once. */
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
        // every test starts on a step no other test used
        clock.advance(Duration.ofMinutes(1));
    }

    private AdminBrowser pending() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        return browser;
    }

    private static String body(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    private String totp(byte[] secret) {
        return AdminMfaTestSupport.codeAt(secret, clock.instant());
    }

    private static byte[] secretOf(HttpResponse<String> enrollment) {
        Matcher m = SECRET.matcher(enrollment.body());
        assertThat(m.find()).as("enrollment body carries a Base32 secret").isTrue();
        return Base32.decode(m.group(1));
    }

    private static List<String> codesOf(HttpResponse<String> response) {
        assertThat(response.body()).matches(CODES);
        List<String> codes = new ArrayList<>();
        Matcher m = CODE.matcher(response.body());
        while (m.find()) {
            codes.add(m.group(1));
        }
        return codes;
    }

    /** The real enrollment: start it, confirm it with the TOTP code, keep the codes of the answer. */
    private Activated activate(AdminBrowser browser) throws Exception {
        byte[] secret = secretOf(browser.post(ENROLLMENT, "{}"));
        HttpResponse<String> confirmation = browser.post(CONFIRMATION, body(totp(secret)));
        assertThat(confirmation.statusCode()).isEqualTo(200);
        return new Activated(secret, codesOf(confirmation));
    }

    private static String correlationId(HttpResponse<String> response) {
        return response.headers().firstValue("X-Correlation-Id").orElseThrow();
    }

    /** "ACTION RESULT" of the audit rows of this Administrator written by the request with {@code correlationId}. */
    private List<String> auditOf(String correlationId) {
        return jdbc.queryForList(
                "select action || ' ' || result from audit_events where actor_id = ? and correlation_id = ?::uuid",
                String.class,
                adminId,
                correlationId);
    }

    /** The code-step rows of this Administrator; each password step writes its own row (AdminAuditTest covers it). */
    private long auditCount() {
        return jdbc.queryForObject(
                "select count(*) from audit_events where actor_id = ? and action <> 'ADMIN_PASSWORD_LOGIN'",
                Long.class,
                adminId);
    }

    private boolean mfaActive() {
        return jdbc.queryForObject(
                "select totp_enabled_at is not null from administrators where id = ?", Boolean.class, adminId);
    }

    private long storedCodes() {
        return jdbc.queryForObject(
                "select count(*) from administrator_recovery_codes where administrator_id = ?", Long.class, adminId);
    }

    /** Makes every SUCCESS insert into audit_events fail while {@code action} runs; failures still go in. */
    private <T> T withSuccessAuditFailing(ThrowingSupplier<T> action) throws Exception {
        jdbc.execute("create function test_audit_down() returns trigger language plpgsql as $$"
                + " begin raise exception 'audit down'; end $$");
        jdbc.execute("create trigger test_audit_down before insert on audit_events for each row"
                + " when (new.result = 'SUCCESS') execute function test_audit_down()");
        try {
            return action.get();
        } finally {
            jdbc.execute("drop trigger test_audit_down on audit_events");
            jdbc.execute("drop function test_audit_down()");
        }
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @Test
    @DisplayName("ADR-0004: a confirmação devolve 10 códigos uma vez, sem cache, e o banco guarda só os hashes")
    void shouldShowTenCodesOnceAndStoreOnlyTheirHashes() throws Exception {
        AdminBrowser browser = pending();
        byte[] secret = secretOf(browser.post(ENROLLMENT, "{}"));

        HttpResponse<String> confirmation = browser.post(CONFIRMATION, body(totp(secret)));

        assertThat(confirmation.statusCode()).isEqualTo(200);
        assertThat(confirmation.headers().firstValue("Cache-Control")).hasValue("no-store");
        List<String> codes = codesOf(confirmation);
        assertThat(codes).hasSize(10).doesNotHaveDuplicates();
        assertThat(jdbc.queryForList(
                        "select code_hash from administrator_recovery_codes where administrator_id = ?"
                                + " and used_at is null",
                        String.class,
                        adminId))
                .containsExactlyInAnyOrderElementsOf(
                        codes.stream().map(Tokens::hash).toList());
        String table = jdbc.queryForObject(
                "select coalesce(string_agg(t::text, ' '), '') from administrator_recovery_codes t", String.class);
        for (String code : codes) {
            assertThat(table).doesNotContain(code).doesNotContain(code.toLowerCase(java.util.Locale.ROOT));
        }
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-065: ativar o MFA grava uma linha ADMIN_MFA_ENABLED SUCCESS com o correlation ID da resposta")
    void shouldAuditTheActivation() throws Exception {
        AdminBrowser browser = pending();
        byte[] secret = secretOf(browser.post(ENROLLMENT, "{}"));
        HttpResponse<String> wrong =
                browser.post(CONFIRMATION, body(AdminMfaTestSupport.wrongCodeAt(secret, clock.instant())));
        assertThat(wrong.statusCode()).isEqualTo(400);

        HttpResponse<String> confirmation = browser.post(CONFIRMATION, body(totp(secret)));

        assertThat(confirmation.statusCode()).isEqualTo(200);
        assertThat(auditOf(correlationId(wrong))).containsExactly("ADMIN_MFA_ENABLED FAILURE");
        assertThat(auditOf(correlationId(confirmation))).containsExactly("ADMIN_MFA_ENABLED SUCCESS");
        assertThat(auditCount()).isEqualTo(2);
        assertThat(jdbc.queryForMap(
                        "select object_type, object_id, host(origin) as origin from audit_events"
                                + " where correlation_id = ?::uuid",
                        correlationId(confirmation)))
                .containsEntry("object_type", "ADMINISTRATOR")
                .containsEntry("object_id", adminId)
                .hasEntrySatisfying("origin", origin -> assertThat(origin).isIn("127.0.0.1", "::1"));
    }

    @Test
    @DisplayName("SEC-065: login com TOTP grava SUCCESS, código errado FAILURE, bloqueio BLOCKED; 409 não grava")
    void shouldAuditTheTotpLogin() throws Exception {
        AdminBrowser notEnrolled = pending();
        HttpResponse<String> conflict = notEnrolled.verifyMfa("123456");
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(auditCount()).isZero();
        Activated activated = activate(pending());
        clock.advance(Duration.ofSeconds(30));
        AdminBrowser browser = pending();

        HttpResponse<String> success = browser.verifyMfa(totp(activated.secret()));
        AdminBrowser other = pending();
        List<HttpResponse<String>> wrong = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            wrong.add(other.verifyMfa(AdminMfaTestSupport.wrongCodeAt(activated.secret(), clock.instant())));
        }
        clock.advance(Duration.ofSeconds(30));
        HttpResponse<String> locked = other.verifyMfa(totp(activated.secret()));

        assertThat(success.statusCode()).isEqualTo(204);
        assertThat(auditOf(correlationId(success))).containsExactly("ADMIN_MFA_LOGIN SUCCESS");
        for (HttpResponse<String> response : wrong) {
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(auditOf(correlationId(response))).containsExactly("ADMIN_MFA_LOGIN FAILURE");
        }
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(auditOf(correlationId(locked))).containsExactly("ADMIN_MFA_LOGIN BLOCKED");
        // the activation, the login, 5 failures and the lock: nothing else
        assertThat(auditCount()).isEqualTo(8);
    }

    @Test
    @DisplayName("SEC-065: sem a trilha a ativação dá 503, nada ativa, nenhum código fica gravado; a falha é gravada")
    void shouldNotActivateWhenTheSuccessAuditFails() throws Exception {
        AdminBrowser browser = pending();
        byte[] secret = secretOf(browser.post(ENROLLMENT, "{}"));

        List<HttpResponse<String>> answers = withSuccessAuditFailing(() -> List.of(
                browser.post(CONFIRMATION, body(AdminMfaTestSupport.wrongCodeAt(secret, clock.instant()))),
                browser.post(CONFIRMATION, body(totp(secret)))));

        assertThat(answers.get(0).statusCode()).isEqualTo(400);
        assertThat(auditOf(correlationId(answers.get(0)))).containsExactly("ADMIN_MFA_ENABLED FAILURE");
        assertThat(answers.get(1).statusCode()).isEqualTo(503);
        assertThat(answers.get(1).body()).doesNotContain("codes");
        assertThat(auditOf(correlationId(answers.get(1)))).isEmpty();
        assertThat(mfaActive()).isFalse();
        assertThat(storedCodes()).isZero();
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
        // the same session and code work once the trail is back: the step was not accepted
        HttpResponse<String> again = browser.post(CONFIRMATION, body(totp(secret)));
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(storedCodes()).isEqualTo(10);
    }

    @Test
    @DisplayName("SEC-065: sem a trilha o login com TOTP dá 503 e o passo não é aceito")
    void shouldNotAcceptTheStepWhenTheSuccessAuditFails() throws Exception {
        Activated activated = activate(pending());
        Long stepBefore =
                jdbc.queryForObject("select totp_last_step from administrators where id = ?", Long.class, adminId);
        clock.advance(Duration.ofSeconds(30));
        AdminBrowser browser = pending();
        String code = totp(activated.secret());

        HttpResponse<String> response = withSuccessAuditFailing(() -> browser.verifyMfa(code));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(auditOf(correlationId(response))).isEmpty();
        assertThat(jdbc.queryForObject("select totp_last_step from administrators where id = ?", Long.class, adminId))
                .isEqualTo(stepBefore)
                .isLessThan(Totp.stepAt(clock.instant()));
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
        assertThat(browser.verifyMfa(code).statusCode()).isEqualTo(204);
    }

    private HttpResponse<String> recover(AdminBrowser browser, String code) throws Exception {
        return browser.post(RECOVERY, body(code));
    }

    private int sessionVersion() {
        return jdbc.queryForObject("select session_version from administrators where id = ?", Integer.class, adminId);
    }

    private boolean used(String code) {
        return jdbc.queryForObject(
                "select used_at is not null from administrator_recovery_codes where code_hash = ?",
                Boolean.class,
                Tokens.hash(code));
    }

    private static String grouped(String code) {
        return String.join("-", code.split("(?<=\\G.{4})"));
    }

    /** Waits until {@code count} requests are blocked on the row lock of administrators that the test holds. */
    private void awaitWaitingOnTheLock(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (jdbc.queryForObject(
                        "select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
                                + " and query like 'update administrators set session_version%'",
                        Long.class)
                < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the requests never reached the row lock");
            }
            Thread.sleep(20);
            // pg_stat_activity is read once per transaction, and this one holds the lock: take a fresh view
            jdbc.execute("select pg_stat_clear_snapshot()");
        }
    }

    @Test
    @DisplayName("ADR-0004 / SEC-065: cada código entra uma vez só; repetido dá 400 CODE_INVALID; cada uso é auditado")
    void shouldAcceptEachCodeOnlyOnce() throws Exception {
        Activated activated = activate(pending());
        String first = activated.codes().getFirst();
        AdminBrowser browser = pending();
        String pendingSession = browser.cookie("JSESSIONID");
        String pendingXsrf = browser.cookie("XSRF-TOKEN");

        HttpResponse<String> success = recover(browser, first);

        assertThat(success.statusCode()).isEqualTo(204);
        assertThat(browser.cookie("JSESSIONID")).isNotBlank().isNotEqualTo(pendingSession);
        assertThat(browser.cookie("XSRF-TOKEN")).isNotBlank().isNotEqualTo(pendingXsrf);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(200);
        assertThat(used(first)).isTrue();
        assertThat(auditOf(correlationId(success))).containsExactly("ADMIN_RECOVERY_CODE_LOGIN SUCCESS");

        AdminBrowser again = pending();
        HttpResponse<String> repeated = recover(again, first);
        assertThat(repeated.statusCode()).isEqualTo(400);
        assertThat(repeated.body()).contains("\"errors\":{\"code\":\"CODE_INVALID\"}");
        assertThat(again.get(CURRENT).statusCode()).isEqualTo(401);
        assertThat(auditOf(correlationId(repeated))).containsExactly("ADMIN_RECOVERY_CODE_LOGIN FAILURE");

        // another code, typed in groups and in lower case, still gets in
        String second = activated.codes().get(1);
        HttpResponse<String> typed = recover(again, grouped(second).toLowerCase(java.util.Locale.ROOT));
        assertThat(typed.statusCode()).isEqualTo(204);
        assertThat(again.get(CURRENT).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "select count(*) from administrator_recovery_codes where administrator_id = ?"
                                + " and used_at is null",
                        Long.class,
                        adminId))
                .isEqualTo(8);
    }

    @Test
    @DisplayName("SEC-006: entrar com um código encerra as outras sessões, completas e pendentes")
    void shouldEndTheOtherSessions() throws Exception {
        AdminBrowser full = pending();
        Activated activated = activate(full);
        assertThat(full.get(CURRENT).statusCode()).isEqualTo(200);
        AdminBrowser otherPending = pending();
        AdminBrowser browser = pending();
        int before = sessionVersion();

        assertThat(recover(browser, activated.codes().getFirst()).statusCode()).isEqualTo(204);

        assertThat(sessionVersion()).isEqualTo(before + 1);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(200);
        assertThat(full.get(CURRENT).statusCode()).isEqualTo(401);
        clock.advance(Duration.ofSeconds(30));
        assertThat(otherPending.verifyMfa(totp(activated.secret())).statusCode())
                .isEqualTo(401);
        // the new session keeps working on later requests: it carries the new version
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("ADR-0004: dois usos simultâneos do mesmo código dão um 204 e um 400")
    void shouldLetOnlyOneOfTwoParallelUsesThrough() throws Exception {
        Activated activated = activate(pending());
        String code = activated.codes().getFirst();
        List<AdminBrowser> browsers = List.of(pending(), pending());
        int before = sessionVersion();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<HttpResponse<String>>> results = new ArrayList<>();
        List<HttpResponse<String>> answers = new ArrayList<>();
        try {
            // the test holds the row lock until both requests wait on it: they have passed the session filter and
            // the code lookup, so they really race on the conditional UPDATE of the code
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForList("select id from administrators where id = ? for update", adminId);
                for (AdminBrowser browser : browsers) {
                    results.add(pool.submit(() -> recover(browser, code)));
                }
                try {
                    awaitWaitingOnTheLock(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            for (Future<HttpResponse<String>> result : results) {
                answers.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(answers).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(204, 400);
        // the loser's version increment was rolled back with its transaction; its failure row was not
        assertThat(sessionVersion()).isEqualTo(before + 1);
        assertThat(answers.stream().flatMap(r -> auditOf(correlationId(r)).stream()))
                .containsExactlyInAnyOrder("ADMIN_RECOVERY_CODE_LOGIN SUCCESS", "ADMIN_RECOVERY_CODE_LOGIN FAILURE");
        assertThat(used(code)).isTrue();
    }

    @Test
    @DisplayName("SEC-014: 5 erros somados entre TOTP e código dão 429 mesmo com o código certo, que não é gasto")
    void shouldShareTheLimitWithTheTotpAndNotSpendTheCode() throws Exception {
        Activated activated = activate(pending());
        AdminBrowser browser = pending();
        for (int i = 0; i < 3; i++) {
            assertThat(browser.verifyMfa(AdminMfaTestSupport.wrongCodeAt(activated.secret(), clock.instant()))
                            .statusCode())
                    .isEqualTo(400);
        }
        // a malformed code counts like a wrong one
        assertThat(recover(browser, "nao-e-um-codigo").statusCode()).isEqualTo(400);
        assertThat(recover(browser, "ABCDEFGHIJKLMNOPQRSTUVWX").statusCode()).isEqualTo(400);

        String code = activated.codes().getFirst();
        HttpResponse<String> locked = recover(browser, code);

        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        assertThat(auditOf(correlationId(locked))).containsExactly("ADMIN_RECOVERY_CODE_LOGIN BLOCKED");
        assertThat(used(code)).isFalse();
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
        clock.advance(Duration.ofSeconds(30));
        assertThat(browser.verifyMfa(totp(activated.secret())).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("ADR-0004: sem MFA ativo o código de recuperação dá 409, sem auditoria")
    void shouldAnswerConflictWithoutActiveMfa() throws Exception {
        HttpResponse<String> response = recover(pending(), "ABCDEFGHIJKLMNOPQRSTUVWX");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(auditCount()).isZero();
    }

    @Test
    @DisplayName("SEC-065: sem a trilha o código dá 503, não é gasto e as outras sessões continuam")
    void shouldNotSpendTheCodeWhenTheSuccessAuditFails() throws Exception {
        AdminBrowser full = pending();
        Activated activated = activate(full);
        String code = activated.codes().getFirst();
        AdminBrowser browser = pending();
        int before = sessionVersion();

        HttpResponse<String> response = withSuccessAuditFailing(() -> recover(browser, code));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(auditOf(correlationId(response))).isEmpty();
        assertThat(used(code)).isFalse();
        assertThat(sessionVersion()).isEqualTo(before);
        assertThat(browser.get(CURRENT).statusCode()).isEqualTo(401);
        assertThat(full.get(CURRENT).statusCode()).isEqualTo(200);
        // once the trail is back the same code works
        assertThat(recover(browser, code).statusCode()).isEqualTo(204);
    }

    private Long lastStep() {
        return jdbc.queryForObject("select totp_last_step from administrators where id = ?", Long.class, adminId);
    }

    private List<String> unusedHashes() {
        return jdbc.queryForList(
                "select code_hash from administrator_recovery_codes where administrator_id = ? and used_at is null",
                String.class,
                adminId);
    }

    private HttpResponse<String> regenerate(AdminBrowser browser, String code) throws Exception {
        return browser.post(REGENERATE, body(code));
    }

    @Test
    @DisplayName("ADR-0004 / SEC-065: gerar de novo com TOTP devolve 10 códigos novos sem cache e os antigos morrem")
    void shouldRegenerateTheCodesAndKillTheOldOnes() throws Exception {
        AdminBrowser full = pending();
        Activated activated = activate(full);
        clock.advance(Duration.ofSeconds(30));

        HttpResponse<String> first = regenerate(full, totp(activated.secret()));

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).hasValue("no-store");
        List<String> renewed = codesOf(first);
        assertThat(renewed).hasSize(10).doesNotHaveDuplicates().doesNotContainAnyElementsOf(activated.codes());
        assertThat(unusedHashes())
                .containsExactlyInAnyOrderElementsOf(
                        renewed.stream().map(Tokens::hash).toList());
        assertThat(storedCodes()).isEqualTo(10);
        assertThat(auditOf(correlationId(first))).containsExactly("ADMIN_RECOVERY_CODES_REGENERATED SUCCESS");
        assertThat(recover(pending(), activated.codes().getFirst()).statusCode())
                .isEqualTo(400);

        // a second regeneration in a row also leaves exactly 10, and kills the first batch
        clock.advance(Duration.ofSeconds(30));
        HttpResponse<String> second = regenerate(full, totp(activated.secret()));

        assertThat(second.statusCode()).isEqualTo(200);
        List<String> latest = codesOf(second);
        assertThat(storedCodes()).isEqualTo(10);
        assertThat(unusedHashes())
                .containsExactlyInAnyOrderElementsOf(
                        latest.stream().map(Tokens::hash).toList());
        assertThat(recover(pending(), renewed.getFirst()).statusCode()).isEqualTo(400);
        AdminBrowser withNewCode = pending();
        assertThat(recover(withNewCode, latest.getFirst()).statusCode()).isEqualTo(204);
        assertThat(withNewCode.get(CURRENT).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-008: o mesmo código TOTP do login é recusado na geração (anti-replay) e os antigos ficam")
    void shouldRefuseTheStepOfTheLoginForTheRegeneration() throws Exception {
        Activated activated = activate(pending());
        clock.advance(Duration.ofSeconds(30));
        AdminBrowser browser = pending();
        String code = totp(activated.secret());
        assertThat(browser.verifyMfa(code).statusCode()).isEqualTo(204);
        List<String> before = unusedHashes();
        Long stepBefore = lastStep();

        HttpResponse<String> replay = regenerate(browser, code);

        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(replay.body()).contains("\"errors\":{\"code\":\"CODE_INVALID\"}");
        assertThat(auditOf(correlationId(replay))).containsExactly("ADMIN_RECOVERY_CODES_REGENERATED FAILURE");
        assertThat(unusedHashes()).hasSize(10).containsExactlyInAnyOrderElementsOf(before);
        assertThat(lastStep()).isEqualTo(stepBefore);
        assertThat(recover(pending(), activated.codes().getFirst()).statusCode())
                .isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-014: TOTP errado na geração mantém os códigos e soma no limite do MFA (4 + 1 dão 429)")
    void shouldKeepTheCodesOnAWrongTotpAndShareTheLimit() throws Exception {
        AdminBrowser full = pending();
        Activated activated = activate(full);
        List<String> before = unusedHashes();
        AdminBrowser other = pending();
        for (int i = 0; i < 4; i++) {
            assertThat(recover(other, "ABCDEFGHIJKLMNOPQRSTUVW" + (char) ('A' + i))
                            .statusCode())
                    .isEqualTo(400);
        }

        HttpResponse<String> wrong =
                regenerate(full, AdminMfaTestSupport.wrongCodeAt(activated.secret(), clock.instant()));

        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(auditOf(correlationId(wrong))).containsExactly("ADMIN_RECOVERY_CODES_REGENERATED FAILURE");
        assertThat(unusedHashes()).containsExactlyInAnyOrderElementsOf(before);
        clock.advance(Duration.ofSeconds(30));
        HttpResponse<String> locked = regenerate(full, totp(activated.secret()));
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        assertThat(locked.body()).doesNotContain("\"codes\"");
        assertThat(auditOf(correlationId(locked))).containsExactly("ADMIN_RECOVERY_CODES_REGENERATED BLOCKED");
        assertThat(unusedHashes()).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    @DisplayName("SEC-014: código malformado na geração conta como erro")
    void shouldCountAMalformedCodeOnTheRegeneration() throws Exception {
        AdminBrowser full = pending();
        Activated activated = activate(full);
        for (String malformed : List.of("", "12345", "abcdef", "1234567", "12 456")) {
            assertThat(regenerate(full, malformed).statusCode()).isEqualTo(400);
        }
        clock.advance(Duration.ofSeconds(30));

        assertThat(regenerate(full, totp(activated.secret())).statusCode()).isEqualTo(429);
        assertThat(storedCodes()).isEqualTo(10);
    }

    @Test
    @DisplayName("ADR-0004: sem MFA ativo a geração dá 409, sem auditoria e sem mexer nos códigos")
    void shouldAnswerConflictOnTheRegenerationWithoutActiveMfa() throws Exception {
        AdminBrowser full = pending();
        Activated activated = activate(full);
        long audited = auditCount();
        // the manual procedure of ADR-0004 clears the activation; the full session is still alive
        jdbc.update("update administrators set totp_enabled_at = null where id = ?", adminId);
        clock.advance(Duration.ofSeconds(30));

        HttpResponse<String> response = regenerate(full, totp(activated.secret()));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(auditCount()).isEqualTo(audited);
        assertThat(storedCodes()).isEqualTo(10);
    }

    @Test
    @DisplayName("SEC-065: sem a trilha a geração dá 503, os códigos antigos ficam e o passo não é aceito")
    void shouldKeepTheOldCodesWhenTheRegenerationAuditFails() throws Exception {
        AdminBrowser full = pending();
        Activated activated = activate(full);
        List<String> before = unusedHashes();
        Long stepBefore = lastStep();
        clock.advance(Duration.ofSeconds(30));
        String code = totp(activated.secret());

        HttpResponse<String> response = withSuccessAuditFailing(() -> regenerate(full, code));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("\"codes\"");
        assertThat(auditOf(correlationId(response))).isEmpty();
        assertThat(unusedHashes()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(lastStep()).isEqualTo(stepBefore);
        // once the trail is back the same code works: the step was not spent
        assertThat(regenerate(full, code).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-008: a geração exige o token CSRF e, sem ele, nada muda")
    void shouldRequireTheCsrfTokenForTheRegeneration() throws Exception {
        AdminBrowser full = pending();
        Activated activated = activate(full);
        List<String> before = unusedHashes();
        clock.advance(Duration.ofSeconds(30));
        String code = totp(activated.secret());

        assertThat(full.postWithoutCsrf(REGENERATE, body(code)).statusCode()).isEqualTo(403);

        assertThat(unusedHashes()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(regenerate(full, code).statusCode()).isEqualTo(200);
    }
}
