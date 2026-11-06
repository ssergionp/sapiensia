package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.SapiensiaApplication;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.MfaProperties;
import br.com.sergio.sapiensia.security.Totp;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bouncycastle.util.encoders.Base32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Guard: no log line may carry a TOTP code, the secret, the encryption key, the cipher text or the Administrator
 * e-mail, through the whole MFA life (enrollment, confirmation, login, wrong code, replay, lock) and a bad-key start.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=admin-mfa-log",
            "app.login-limit.ip-max-attempts=1000",
            // the noisiest levels that could print an Authentication, a request body or a SQL parameter
            "logging.level.org.springframework.security=TRACE",
            "logging.level.org.springframework.web=DEBUG",
            "logging.level.br.com.sergio.sapiensia=TRACE"
        })
class AdminMfaLogTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow
    private static final String MFA = "/api/admin/sessions/mfa";
    private static final String ENROLLMENT = MFA + "/enrollment";
    private static final String CONFIRMATION = ENROLLMENT + "/confirmation";
    private static final Pattern SECRET = Pattern.compile("\"secret\":\"([A-Z2-7]{32})\"");
    private static final Pattern OTPAUTH = Pattern.compile("\"otpauthUri\":\"([^\"]+)\"");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    Environment environment;

    @Autowired
    AdminMfaTestSupport.MutableClock clock;

    private static String body(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    private static void assertNoneOf(String output, Map<String, String> forbidden) {
        forbidden.forEach((what, value) ->
                assertThat(output).as("the output must not contain %s", what).doesNotContain(value));
    }

    /** A 6-digit code is only searched as a whole token, so a timestamp or an id cannot match by accident. */
    private static void assertNoCode(String output, String code) {
        assertThat(Pattern.compile("(?<![0-9A-Za-z])" + code + "(?![0-9A-Za-z])")
                        .matcher(output)
                        .find())
                .as("the output must not contain the code %s", code)
                .isFalse();
    }

    @Test
    @DisplayName("SEC-003: enrolling, confirming, logging in, a wrong code, a replay, a 429 lock and a start with a bad"
            + " key leave no code, secret, key, cipher text or e-mail in the logs")
    void shouldNotLeakSecretsOrCodesToTheLogs(CapturedOutput output) throws Exception {
        jdbc.update("delete from administrators");
        String email = "painel-log-" + UUID.randomUUID() + "@example.com";
        UUID adminId = UUID.randomUUID();
        jdbc.update(
                "insert into administrators (id, email, password_hash) values (?, ?, ?)",
                adminId,
                email,
                passwordEncoder.encode(PASSWORD));
        clock.advance(Duration.ofMinutes(1));
        List<String> codes = new ArrayList<>();

        // enrollment and confirmation
        AdminBrowser first = new AdminBrowser(port);
        assertThat(first.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        HttpResponse<String> enrollment = first.post(ENROLLMENT, "{}");
        assertThat(enrollment.statusCode()).isEqualTo(200);
        Matcher secretMatcher = SECRET.matcher(enrollment.body());
        assertThat(secretMatcher.find()).isTrue();
        String base32 = secretMatcher.group(1);
        byte[] secret = Base32.decode(base32);
        Matcher uriMatcher = OTPAUTH.matcher(enrollment.body());
        assertThat(uriMatcher.find()).isTrue();
        String otpauth = uriMatcher.group(1);
        String confirmCode = AdminMfaTestSupport.codeAt(secret, clock.instant());
        codes.add(confirmCode);
        assertThat(first.post(CONFIRMATION, body(confirmCode)).statusCode()).isEqualTo(204);

        // a correct login on a fresh step
        clock.advance(Duration.ofSeconds(30));
        String loginCode = AdminMfaTestSupport.codeAt(secret, clock.instant());
        codes.add(loginCode);
        AdminBrowser second = new AdminBrowser(port);
        assertThat(second.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        assertThat(second.verifyMfa(loginCode).statusCode()).isEqualTo(204);

        // a wrong code, then a replay of the code just used
        AdminBrowser third = new AdminBrowser(port);
        assertThat(third.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        String wrong = AdminMfaTestSupport.wrongCodeAt(secret, clock.instant());
        codes.add(wrong);
        assertThat(third.verifyMfa(wrong).statusCode()).isEqualTo(400);
        assertThat(third.verifyMfa(loginCode).statusCode()).isEqualTo(400);

        // the lock: the failures above already count 2 of 5, so three more fill it and the next answer is 429
        for (int i = 0; i < 3; i++) {
            assertThat(third.verifyMfa(wrong).statusCode()).isEqualTo(400);
        }
        clock.advance(Duration.ofSeconds(30));
        String lockedCode = AdminMfaTestSupport.codeAt(secret, clock.instant());
        codes.add(lockedCode);
        assertThat(third.verifyMfa(lockedCode).statusCode()).isEqualTo(429);

        String cipherText = jdbc.queryForObject("select totp_secret from administrators", String.class);
        String key = environment.getProperty("app.mfa.encryption-key");
        String badKey = Base64.getEncoder().encodeToString("sixteen-byte-key".getBytes(StandardCharsets.UTF_8));

        Map<String, String> forbidden = new LinkedHashMap<>();
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
        forbidden.put("the Administrator e-mail", email);
        forbidden.put("the Administrator e-mail, URL-encoded", email.replace("@", "%40"));
        forbidden.put("the password", PASSWORD);

        String flow = output.getAll();
        assertThat(flow).as("the flow logged something").isNotBlank();
        assertNoneOf(flow, forbidden);
        codes.forEach(code -> assertNoCode(flow, code));

        // a start with an invalid key: it fails, and its output carries neither the key nor the e-mail
        int before = output.getAll().length();
        try {
            new SpringApplicationBuilder(SapiensiaApplication.class)
                    .web(WebApplicationType.SERVLET)
                    // command-line arguments win over the test application.properties; builder properties would not
                    .run(
                            "--app.mfa.encryption-key=" + badKey,
                            "--spring.datasource.url=" + environment.getProperty("spring.datasource.url"),
                            "--spring.datasource.username=" + environment.getProperty("spring.datasource.username"),
                            "--spring.datasource.password=" + environment.getProperty("spring.datasource.password"),
                            "--app.email.provider=file",
                            "--app.email.outbox-dir=target/log-test-outbox",
                            "--spring.main.banner-mode=off",
                            "--server.port=0",
                            "--test.isolated-context=admin-mfa-log-badkey")
                    .close();
            throw new AssertionError("the application started with an invalid key");
        } catch (RuntimeException expected) {
            assertThat(String.valueOf(expected.getMessage()))
                    .doesNotContain(badKey)
                    .doesNotContain(key);
        }
        String startup = output.getAll().substring(before);
        assertThat(startup).contains("Invalid app.mfa.encryption-key");
        Map<String, String> startupForbidden = new LinkedHashMap<>(forbidden);
        startupForbidden.put("the invalid key", badKey);
        assertNoneOf(startup, startupForbidden);
    }

    @Test
    @DisplayName("SEC-003: toString of the MFA records and properties shows no code, secret, key or e-mail")
    void shouldRedactToString() {
        String email = "alguem@example.com";
        byte[] secret = Totp.newSecret();
        String base32 = Totp.toBase32(secret);
        String uri = Totp.otpauthUri("SapiensIA", email, secret);
        String key = environment.getProperty("app.mfa.encryption-key");
        UUID id = UUID.randomUUID();
        List<String> shown = List.of(
                new MfaCodeRequest("123456").toString(),
                new AdministratorMfa.Enrollment(base32, uri).toString(),
                new PendingAdministrator(id, email, 0, Instant.now()).toString(),
                new AdministratorPrincipal(id, email, 0, EnumSet.allOf(Capability.class), Instant.now(), Instant.now())
                        .toString(),
                new MfaProperties(key).toString());

        for (String text : shown) {
            assertThat(text)
                    .doesNotContain("123456")
                    .doesNotContain(base32)
                    .doesNotContain(uri)
                    .doesNotContain(key)
                    .doesNotContain(email)
                    .doesNotContain(email.replace("@", "%40"));
        }
    }
}
