package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import br.com.sergio.sapiensia.security.Tokens;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bouncycastle.util.encoders.Base32;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Guard for ADR-0006 / SEC-036 / SEC-065: one long flow of the panel (and of the Customer login) with the noisiest log
 * levels and the production log format (ECS JSON), then a search of the whole captured log and of the audit rows for
 * everything that must never be stored: e-mails, CPF, passwords, the link token, recovery codes, hashes, the TOTP
 * secret and codes, the encryption key and the cipher text. audit_events is append-only, so the rows are selected by
 * the correlation ids of this test's own requests. The exact column list of audit_events is fixed by AuditLogTest.
 *
 * <p>Logback is global to the JVM: this class configures its logging from scratch (so the ECS format applies even
 * after another class configured it) and leaves the default logging behind (so its levels and format do not reach the
 * classes that run after it, including those that reuse a cached context).
 */
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=audit-privacy",
            "app.login-limit.ip-max-attempts=1000",
            // production trusts a platform header; here it is X-Client-Ip, so a forged value reaches the resolver
            "app.login-limit.client-ip-header=X-Client-Ip",
            // the production log format
            "logging.structured.format.console=ecs",
            "logging.level.org.springframework.security=TRACE",
            "logging.level.org.springframework.web=DEBUG",
            "logging.level.br.com.sergio.sapiensia=TRACE"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuditPrivacyTest {

    private static final String PASSWORD = "senha-do-painel-9876"; // gitleaks:allow
    private static final String WRONG_PASSWORD = "senha-errada-do-painel-5432"; // gitleaks:allow
    private static final String CUSTOMER_EMAIL = "cliente-privado@example.com";
    private static final String CPF_MASKED = "123.456.789-09"; // fictitious
    private static final String CPF_DIGITS = "12345678909";
    private static final String SESSIONS = "/api/admin/sessions";
    private static final String MFA = SESSIONS + "/mfa";
    private static final String ENROLLMENT = MFA + "/enrollment";
    private static final String CONFIRMATION = ENROLLMENT + "/confirmation";
    private static final String RECOVERY = MFA + "/recovery-code";
    private static final String REGENERATE = "/api/admin/recovery-codes";
    private static final String SETUP = "/api/admin/password-setup";
    private static final Pattern SECRET = Pattern.compile("\"secret\":\"([A-Z2-7]{32})\"");
    private static final Pattern OTPAUTH = Pattern.compile("\"otpauthUri\":\"([^\"]+)\"");
    private static final Pattern RECOVERY_CODE = Pattern.compile("\"([A-Z2-7]{24})\"");
    // the timestamp of a row has microseconds that could look like a 6-digit TOTP code
    private static final Pattern ROW_TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2} [0-9:.]+[+-]\\d+");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AdminMfaTestSupport.MutableClock clock;

    @Autowired
    Environment environment;

    /** The JVM system properties before this class; the logging setup writes some (the console format among them). */
    private static Properties systemPropertiesBefore;

    /**
     * Spring Boot skips the logging setup when an earlier context already did it, so the ECS format and the levels of
     * this class would be ignored. Marking the logging system as not initialized makes this class's context configure
     * it from scratch.
     */
    @BeforeAll
    static void configureTheLoggingFromScratch() {
        systemPropertiesBefore = (Properties) System.getProperties().clone();
        LoggingSystem.get(AuditPrivacyTest.class.getClassLoader()).cleanUp();
    }

    /**
     * The logging setup keeps the console format in a system property (CONSOLE_LOG_STRUCTURED_FORMAT) that a later
     * setup does not clear, and a cached context of another class never configures the logging again, so both would
     * keep the ECS format and the TRACE levels of this class. Those system properties go back to what they were, then a
     * bare application (no bean, no web server) configures the logging again from the same application files every
     * test context reads, without the properties of this class.
     */
    @AfterAll
    static void restoreTheDefaultLogging() {
        // the logging setup names its system properties in upper case (CONSOLE_LOG_STRUCTURED_FORMAT,
        // LOG_LEVEL_PATTERN)
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.matches("[A-Z_]+")) {
                String before = systemPropertiesBefore.getProperty(name);
                if (before == null) {
                    System.clearProperty(name);
                } else {
                    System.setProperty(name, before);
                }
            }
        }
        LoggingSystem.get(AuditPrivacyTest.class.getClassLoader()).cleanUp();
        new SpringApplicationBuilder(DefaultLogging.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .registerShutdownHook(false)
                .run()
                .close();
    }

    /** The only source of the bare application; not a configuration class, so @SpringBootTest does not pick it up. */
    static class DefaultLogging {}

    /** The correlation ids the server returned for the requests of this test. */
    private final List<String> ids = new ArrayList<>();

    private HttpResponse<String> seen(HttpResponse<String> response) {
        ids.add(correlationId(response));
        return response;
    }

    private static String correlationId(HttpResponse<String> response) {
        return response.headers().firstValue("X-Correlation-Id").orElseThrow();
    }

    private static String body(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private HttpResponse<String> login(AdminBrowser browser, String email, String password) throws Exception {
        return seen(browser.post(SESSIONS, loginBody(email, password)));
    }

    private HttpResponse<String> post(AdminBrowser browser, String path, String json) throws Exception {
        return seen(browser.post(path, json));
    }

    private static List<String> codesOf(HttpResponse<String> response) {
        Matcher matcher = RECOVERY_CODE.matcher(response.body());
        List<String> codes = new ArrayList<>();
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        assertThat(codes).hasSize(10);
        return codes;
    }

    private String totp(byte[] secret) {
        return AdminMfaTestSupport.codeAt(secret, clock.instant());
    }

    private static void assertNoneOf(String where, String text, Map<String, String> forbidden) {
        forbidden.forEach((what, value) ->
                assertThat(text).as("%s must not contain %s", where, what).doesNotContain(value));
    }

    /** A 6-digit code is only searched as a whole token, so a timestamp or an id cannot match by accident. */
    private static void assertNoCode(String where, String text, String code) {
        assertThat(Pattern.compile("(?<![0-9A-Za-z])" + code + "(?![0-9A-Za-z])")
                        .matcher(text)
                        .find())
                .as("%s must not contain the code %s", where, code)
                .isFalse();
    }

    /** The ids as a SQL list; they are parsed as UUIDs first, so nothing else can reach the query. */
    private String idList() {
        return ids.stream().map(id -> "'" + UUID.fromString(id) + "'").collect(Collectors.joining(","));
    }

    /** The audit rows of this test's requests as a reader of the table sees them. */
    private String rowsAsText() {
        return jdbc.queryForObject(
                "select coalesce(string_agg(t::text, ' '), '') from audit_events t where correlation_id::text in ("
                        + idList() + ")",
                String.class);
    }

    private List<String> actionsOf(String correlationId) {
        return jdbc.queryForList(
                "select action || ' ' || result || ' ' || coalesce(actor_id::text, 'none') from audit_events"
                        + " where correlation_id = ?::uuid",
                String.class,
                correlationId);
    }

    private static List<String> variants(String code) {
        return List.of(
                code,
                code.toLowerCase(Locale.ROOT),
                code.replaceAll("(.{4})(?=.)", "$1-"),
                code.replaceAll("(.{4})(?=.)", "$1 "),
                code.toLowerCase(Locale.ROOT).replaceAll("(.{4})(?=.)", "$1-"));
    }

    @Test
    @DisplayName("ADR-0006 / SEC-036 / SEC-065: the whole panel flow with TRACE logs in ECS and the audit rows leave no"
            + " e-mail, CPF, password, token, recovery code, hash, TOTP secret or code, key or cipher text")
    void shouldKeepSensitiveDataOutOfTheLogsAndTheAuditTrail(CapturedOutput output) throws Exception {
        // a first-access account: the link token is the first secret of the flow
        jdbc.update("delete from scheduled_tasks where task_name = ?", AdministratorEmailTasks.PASSWORD_SETUP);
        jdbc.update("delete from administrators");
        UUID adminId = UUID.randomUUID();
        String adminEmail = "painel-privado-" + UUID.randomUUID() + "@example.com";
        jdbc.update("insert into administrators (id, email) values (?, ?)", adminId, adminEmail);
        String token = Tokens.newToken();
        jdbc.update(
                "insert into administrator_password_tokens (administrator_id, token_hash, expires_at)"
                        + " values (?, ?, now() + interval '1 hour')",
                adminId,
                Tokens.hash(token));
        clock.advance(Duration.ofMinutes(1));

        // the link: a wrong use, a right use, then the used link again
        AdminBrowser setup = new AdminBrowser(port);
        String wrongToken = Tokens.newToken();
        String wrongSetup = "{\"token\":\"" + wrongToken + "\",\"newPassword\":\"" + PASSWORD + "\"}";
        String rightSetup = "{\"token\":\"" + token + "\",\"newPassword\":\"" + PASSWORD + "\"}";
        assertThat(post(setup, SETUP, wrongSetup).statusCode()).isEqualTo(400);
        HttpResponse<String> set = post(setup, SETUP, rightSetup);
        assertThat(set.statusCode()).isEqualTo(204);
        assertThat(actionsOf(correlationId(set))).containsExactly("ADMIN_PASSWORD_SET SUCCESS " + adminId);
        assertThat(post(setup, SETUP, rightSetup).statusCode()).isEqualTo(400);

        // wrong passwords with data of other people typed in the wrong field
        AdminBrowser typing = new AdminBrowser(port);
        HttpResponse<String> customerEmailTyped = login(typing, CUSTOMER_EMAIL, WRONG_PASSWORD);
        assertThat(customerEmailTyped.statusCode()).isEqualTo(401);
        assertThat(actionsOf(correlationId(customerEmailTyped))).containsExactly("ADMIN_PASSWORD_LOGIN FAILURE none");
        assertThat(login(typing, CPF_MASKED, CPF_MASKED).statusCode()).isEqualTo(401);
        assertThat(login(typing, CPF_DIGITS, CPF_DIGITS).statusCode()).isEqualTo(401);
        assertThat(login(typing, adminEmail, CPF_MASKED).statusCode()).isEqualTo(401);
        assertThat(login(typing, adminEmail, CPF_DIGITS).statusCode()).isEqualTo(401);
        assertThat(login(typing, adminEmail, WRONG_PASSWORD).statusCode()).isEqualTo(401);
        assertThat(login(typing, "ninguem-" + UUID.randomUUID() + "@example.com", WRONG_PASSWORD)
                        .statusCode())
                .isEqualTo(401);
        // the same typing on the Customer login
        assertThat(seen(typing.loginCustomer(CUSTOMER_EMAIL, WRONG_PASSWORD)).statusCode())
                .isEqualTo(401);
        assertThat(seen(typing.loginCustomer(CPF_MASKED, CPF_DIGITS)).statusCode())
                .isEqualTo(401);

        // forged headers: the correlation id and both IP headers carry a Customer e-mail
        Map<String, String> forgedHeaders = Map.of(
                "X-Correlation-Id", CUSTOMER_EMAIL, "X-Forwarded-For", CUSTOMER_EMAIL, "X-Client-Ip", CUSTOMER_EMAIL);
        HttpResponse<String> forged =
                seen(typing.postWithHeaders(SESSIONS, loginBody(adminEmail, WRONG_PASSWORD), forgedHeaders));
        assertThat(forged.statusCode()).isEqualTo(401);
        assertThat(correlationId(forged)).isNotEqualTo(CUSTOMER_EMAIL);
        assertThat(UUID.fromString(correlationId(forged))).isNotNull();
        assertThat(actionsOf(correlationId(forged))).containsExactly("ADMIN_PASSWORD_LOGIN FAILURE " + "none");
        HttpResponse<String> forgedOk =
                seen(typing.postWithHeaders(SESSIONS, loginBody(adminEmail, PASSWORD), forgedHeaders));
        assertThat(forgedOk.statusCode()).isEqualTo(204);
        assertThat(correlationId(forgedOk)).isNotEqualTo(CUSTOMER_EMAIL);

        // correct password, enrollment, confirmation: the 10 codes come back once
        AdminBrowser first = new AdminBrowser(port);
        HttpResponse<String> correct = login(first, adminEmail, PASSWORD);
        assertThat(correct.statusCode()).isEqualTo(204);
        assertThat(actionsOf(correlationId(correct))).containsExactly("ADMIN_PASSWORD_LOGIN SUCCESS " + adminId);
        HttpResponse<String> enrollment = post(first, ENROLLMENT, "{}");
        assertThat(enrollment.statusCode()).isEqualTo(200);
        Matcher secretMatcher = SECRET.matcher(enrollment.body());
        assertThat(secretMatcher.find()).isTrue();
        String base32 = secretMatcher.group(1);
        byte[] secret = Base32.decode(base32);
        Matcher uriMatcher = OTPAUTH.matcher(enrollment.body());
        assertThat(uriMatcher.find()).isTrue();
        String otpauth = uriMatcher.group(1);
        List<String> totpCodes = new ArrayList<>();
        String confirmCode = totp(secret);
        totpCodes.add(confirmCode);
        HttpResponse<String> confirmation = post(first, CONFIRMATION, body(confirmCode));
        assertThat(confirmation.statusCode()).isEqualTo(200);
        assertThat(actionsOf(correlationId(confirmation))).containsExactly("ADMIN_MFA_ENABLED SUCCESS " + adminId);
        List<String> initialCodes = codesOf(confirmation);
        List<String> recoveryCodes = new ArrayList<>(initialCodes);

        // a TOTP login on a fresh step
        clock.advance(Duration.ofSeconds(30));
        String loginCode = totp(secret);
        totpCodes.add(loginCode);
        AdminBrowser second = new AdminBrowser(port);
        assertThat(login(second, adminEmail, PASSWORD).statusCode()).isEqualTo(204);
        HttpResponse<String> mfaLogin = post(second, MFA, body(loginCode));
        assertThat(mfaLogin.statusCode()).isEqualTo(204);
        assertThat(actionsOf(correlationId(mfaLogin))).containsExactly("ADMIN_MFA_LOGIN SUCCESS " + adminId);

        // a recovery code: right, repeated and wrong
        AdminBrowser third = new AdminBrowser(port);
        assertThat(login(third, adminEmail, PASSWORD).statusCode()).isEqualTo(204);
        HttpResponse<String> recovered = post(third, RECOVERY, body(initialCodes.get(0)));
        assertThat(recovered.statusCode()).isEqualTo(204);
        assertThat(actionsOf(correlationId(recovered))).containsExactly("ADMIN_RECOVERY_CODE_LOGIN SUCCESS " + adminId);
        AdminBrowser fourth = new AdminBrowser(port);
        assertThat(login(fourth, adminEmail, PASSWORD).statusCode()).isEqualTo(204);
        HttpResponse<String> repeated = post(fourth, RECOVERY, body(initialCodes.get(0)));
        assertThat(repeated.statusCode()).isEqualTo(400);
        assertThat(actionsOf(correlationId(repeated))).containsExactly("ADMIN_RECOVERY_CODE_LOGIN FAILURE " + adminId);
        String neverIssued = "AAAABBBBCCCCDDDDEEEEFFFF";
        assertThat(post(fourth, RECOVERY, body(neverIssued)).statusCode()).isEqualTo(400);

        // regeneration: a wrong TOTP code, then the right one (the 10 new codes come back once)
        clock.advance(Duration.ofSeconds(30));
        String wrongTotp = AdminMfaTestSupport.wrongCodeAt(secret, clock.instant());
        totpCodes.add(wrongTotp);
        assertThat(post(third, REGENERATE, body(wrongTotp)).statusCode()).isEqualTo(400);
        String regenCode = totp(secret);
        totpCodes.add(regenCode);
        HttpResponse<String> regen = post(third, REGENERATE, body(regenCode));
        assertThat(regen.statusCode()).isEqualTo(200);
        assertThat(actionsOf(correlationId(regen)))
                .containsExactly("ADMIN_RECOVERY_CODES_REGENERATED SUCCESS " + adminId);
        recoveryCodes.addAll(codesOf(regen));

        // the lock: wrong recovery codes until the answer is 429
        AdminBrowser locking = new AdminBrowser(port);
        int status = 0;
        for (int i = 0; i < 8 && status != 429; i++) {
            HttpResponse<String> attempt = login(locking, adminEmail, PASSWORD);
            if (attempt.statusCode() == 204) {
                attempt = post(locking, RECOVERY, body(neverIssued));
            }
            status = attempt.statusCode();
        }
        assertThat(status).as("the account ends locked").isEqualTo(429);

        String cipherText =
                jdbc.queryForObject("select totp_secret from administrators where id = ?", String.class, adminId);
        String key = environment.getProperty("app.mfa.encryption-key");

        Map<String, String> forbidden = new LinkedHashMap<>();
        forbidden.put("the Customer e-mail", CUSTOMER_EMAIL);
        forbidden.put("the Customer e-mail, URL-encoded", CUSTOMER_EMAIL.replace("@", "%40"));
        forbidden.put("the Administrator e-mail", adminEmail);
        forbidden.put("the Administrator e-mail, URL-encoded", adminEmail.replace("@", "%40"));
        forbidden.put("the masked CPF", CPF_MASKED);
        forbidden.put("the CPF digits", CPF_DIGITS);
        forbidden.put("the password", PASSWORD);
        forbidden.put("the wrong password", WRONG_PASSWORD);
        forbidden.put("the link token", token);
        forbidden.put("the wrong link token", wrongToken);
        forbidden.put("the hash of the link token", Tokens.hash(token));
        forbidden.put("the Base32 secret", base32);
        forbidden.put("the secret in Base64", Base64.getEncoder().encodeToString(secret));
        forbidden.put("the secret in URL-safe Base64", Base64.getUrlEncoder().encodeToString(secret));
        forbidden.put("the secret in lower-case hex", HexFormat.of().formatHex(secret));
        forbidden.put(
                "the secret in upper-case hex", HexFormat.of().withUpperCase().formatHex(secret));
        forbidden.put("the otpauth URI", otpauth);
        forbidden.put("the otpauth scheme", "otpauth://");
        forbidden.put("the encryption key", key);
        forbidden.put("the stored cipher text", cipherText);
        forbidden.put("the never issued recovery code", neverIssued);
        for (int i = 0; i < recoveryCodes.size(); i++) {
            String code = recoveryCodes.get(i);
            for (String variant : variants(code)) {
                forbidden.put("recovery code " + i + " as " + variant, variant);
            }
            forbidden.put("the hash of recovery code " + i, RecoveryCodes.hash(code));
        }

        String log = output.getAll();
        String rows = rowsAsText();
        assertThat(log).as("the flow logged something").isNotBlank();
        assertThat(rows).as("the flow was audited").isNotBlank();
        assertNoneOf("the log", log, forbidden);
        assertNoneOf("the audit rows", rows, forbidden);
        String rowsWithoutTimestamps = ROW_TIMESTAMP.matcher(rows).replaceAll("");
        for (String code : totpCodes) {
            assertNoCode("the log", log, code);
            assertNoCode("the audit rows", rowsWithoutTimestamps, code);
        }

        // the guard is not vacuous: the trail kept what it must
        assertThat(rows)
                .contains("ADMIN_PASSWORD_SET", "ADMIN_PASSWORD_LOGIN", "ADMIN_MFA_ENABLED", "ADMIN_MFA_LOGIN")
                .contains("ADMIN_RECOVERY_CODE_LOGIN", "ADMIN_RECOVERY_CODES_REGENERATED", "BLOCKED", "FAILURE")
                .contains(adminId.toString());
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'ADMIN_PASSWORD_LOGIN'"
                                + " and result = 'FAILURE' and actor_id is not null and correlation_id::text in ("
                                + idList() + ")",
                        Long.class))
                .as("a wrong password identifies nobody")
                .isZero();

        // the production log format: ECS JSON, with the correlation id of the request as an MDC field
        String forgedId = correlationId(forged);
        List<String> forgedLines =
                log.lines().filter(line -> line.contains(forgedId)).toList();
        assertThat(forgedLines)
                .as("a log line carries the correlation id of the request")
                .isNotEmpty();
        assertThat(forgedLines)
                .anySatisfy(line -> assertThat(line)
                        .startsWith("{\"@timestamp\"")
                        .contains("\"correlationId\":\"" + forgedId + "\""));
    }

    @Test
    @DisplayName("ADR-0006 / SEC-036: toString of the records that carry codes, hashes, secrets or credentials shows"
            + " none")
    void shouldRedactToStringOfTheSensitiveRecords() {
        String code = "ABCDEFGHIJKLMNOPQRSTUVWX";
        String hash = RecoveryCodes.hash(code);
        String email = "alguem-privado@example.com";
        String password = "senha-secreta-123456"; // gitleaks:allow
        String token = Tokens.newToken();
        String secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
        String uri = "otpauth://totp/SapiensIA:" + email + "?secret=" + secret;
        UUID id = UUID.randomUUID();
        List<Object> records = List.of(
                new RecoveryCodeList(List.of(code)),
                AdministratorMfa.Outcome.withCodes(List.of(code)),
                new RecoveryCodes.Unused(id, hash),
                new AdminLoginRequest(email, password),
                new AdminPasswordSetupRequest(token, password),
                new MfaCodeRequest("123456"),
                new AdministratorMfa.Enrollment(secret, uri),
                new AdministratorMfa.Started(new AdministratorMfa.Enrollment(secret, uri), "v1:cipher-text"),
                new Administrators.Login(id, email, hash, 0),
                new Administrators.StoredTotp("v1:cipher-text", true),
                new AdministratorUserDetailsService.Loaded(id, email, 0, hash),
                new PendingAdministrator(id, email, 0, Instant.now()),
                new AdministratorPrincipal(
                        id, email, 0, EnumSet.allOf(Capability.class), Instant.now(), Instant.now()));

        for (Object record : records) {
            assertThat(record.toString())
                    .as(record.getClass().getSimpleName())
                    .doesNotContain(code)
                    .doesNotContain(hash)
                    .doesNotContain(email)
                    .doesNotContain(password)
                    .doesNotContain(token)
                    .doesNotContain(secret)
                    .doesNotContain(uri)
                    .doesNotContain("123456")
                    .doesNotContain("cipher-text");
        }
        // the audit entry has no free text at all: only enums, ids and the validated IP
        String entry = new AuditEntry(
                        AuditAction.ADMIN_RECOVERY_CODE_LOGIN,
                        AuditResult.FAILURE,
                        id,
                        AuditObject.ADMINISTRATOR,
                        id,
                        "203.0.113.7")
                .toString();
        assertThat(entry).doesNotContain(code).doesNotContain(hash).doesNotContain(email);
    }
}
