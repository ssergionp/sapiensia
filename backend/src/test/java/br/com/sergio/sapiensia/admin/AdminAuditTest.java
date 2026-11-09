package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import br.com.sergio.sapiensia.security.Tokens;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * SEC-065: the audit trail of the password steps of the panel (the login password and the password set by the link),
 * against the real server and database. audit_events is append-only, so every check filters by the correlation id of
 * its own request (or by this test's Administrator).
 */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-audit", "app.login-limit.ip-max-attempts=1000"})
class AdminAuditTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow
    private static final String WRONG = "senha-errada-45678";
    private static final String NEW_PASSWORD = "senha-nova-do-painel-4567"; // gitleaks:allow
    private static final String CURRENT = "/api/admin/sessions/current";
    private static final String SETUP = "/api/admin/password-setup";

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
        // a pending first-access e-mail of the startup bootstrap would replace this test's links when it runs
        jdbc.update("delete from scheduled_tasks where task_name = ?", AdministratorEmailTasks.PASSWORD_SETUP);
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

    private AdminBrowser browser() {
        return new AdminBrowser(port);
    }

    private static String correlationId(HttpResponse<String> response) {
        return response.headers().firstValue("X-Correlation-Id").orElseThrow();
    }

    /** Every audit row written by the request with {@code correlationId}, whole. */
    private List<Map<String, Object>> rowsOf(String correlationId) {
        return jdbc.queryForList(
                "select action, result, actor_id, object_type, object_id, host(origin) as origin"
                        + " from audit_events where correlation_id = ?::uuid",
                correlationId);
    }

    /** The whole rows of the request as text: what a reader of the table would see. */
    private String rowTextOf(String correlationId) {
        return jdbc.queryForObject(
                "select coalesce(string_agg(t::text, ' '), '') from audit_events t where correlation_id = ?::uuid",
                String.class,
                correlationId);
    }

    private List<String> actionsOf(String correlationId) {
        return jdbc.queryForList(
                "select action || ' ' || result from audit_events where correlation_id = ?::uuid",
                String.class,
                correlationId);
    }

    /** Makes every SUCCESS insert of {@code action} into audit_events fail while {@code body} runs. */
    private <T> T withSuccessAuditFailing(String action, ThrowingSupplier<T> body) throws Exception {
        jdbc.execute("create function test_password_audit_down() returns trigger language plpgsql as $$"
                + " begin raise exception 'audit down'; end $$");
        jdbc.execute("create trigger test_password_audit_down before insert on audit_events for each row"
                + " when (new.result = 'SUCCESS' and new.action = '" + action + "')"
                + " execute function test_password_audit_down()");
        try {
            return body.get();
        } finally {
            jdbc.execute("drop trigger test_password_audit_down on audit_events");
            jdbc.execute("drop function test_password_audit_down()");
        }
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @Test
    @DisplayName("SEC-065: senha certa grava ADMIN_PASSWORD_LOGIN SUCCESS com o Administrador e o correlation ID")
    void shouldAuditTheCorrectPassword() throws Exception {
        HttpResponse<String> login = browser().loginAdmin(email, PASSWORD);

        assertThat(login.statusCode()).isEqualTo(204);
        List<Map<String, Object>> rows = rowsOf(correlationId(login));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("action", "ADMIN_PASSWORD_LOGIN")
                .containsEntry("result", "SUCCESS")
                .containsEntry("actor_id", adminId)
                .containsEntry("object_type", "ADMINISTRATOR")
                .containsEntry("object_id", adminId)
                .hasEntrySatisfying("origin", origin -> assertThat(origin).isIn("127.0.0.1", "::1"));
    }

    @Test
    @DisplayName("SEC-065: senha errada grava FAILURE sem ator, sem objeto e sem o e-mail digitado, nem no log")
    void shouldAuditTheWrongPasswordWithoutIdentifyingAnyone(CapturedOutput output) throws Exception {
        String customerLike = "cliente-" + UUID.randomUUID() + "@example.com";
        String unknown = "ninguem-" + UUID.randomUUID() + "@example.com";
        AdminBrowser browser = browser();

        List<HttpResponse<String>> failures = List.of(
                browser.loginAdmin(email, WRONG),
                browser.loginAdmin(customerLike, PASSWORD),
                // the password typed in the e-mail field must not reach the trail either
                browser.loginAdmin(WRONG, PASSWORD),
                browser.loginAdmin(unknown, WRONG));

        for (HttpResponse<String> failure : failures) {
            assertThat(failure.statusCode()).isEqualTo(401);
            List<Map<String, Object>> rows = rowsOf(correlationId(failure));
            assertThat(rows).hasSize(1);
            Map<String, Object> row = rows.getFirst();
            assertThat(row)
                    .containsEntry("action", "ADMIN_PASSWORD_LOGIN")
                    .containsEntry("result", "FAILURE")
                    .hasEntrySatisfying("origin", origin -> assertThat(origin).isIn("127.0.0.1", "::1"));
            assertThat(row.get("actor_id")).isNull();
            assertThat(row.get("object_type")).isNull();
            assertThat(row.get("object_id")).isNull();
            assertThat(rowTextOf(correlationId(failure)))
                    .doesNotContain(email)
                    .doesNotContain(customerLike)
                    .doesNotContain(unknown)
                    .doesNotContain(WRONG)
                    .doesNotContain(PASSWORD)
                    .doesNotContain(adminId.toString());
        }
        assertThat(output.getAll())
                .doesNotContain(email)
                .doesNotContain(customerLike)
                .doesNotContain(unknown)
                .doesNotContain(WRONG)
                .doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("SEC-065: o 429 da senha e a entrada recusada antes do limitador não são auditados")
    void shouldNotAuditTheLockOrTheInputRefusedBeforeTheLimiter() throws Exception {
        AdminBrowser browser = browser();
        List<HttpResponse<String>> refused = List.of(
                browser.loginAdmin("", PASSWORD),
                browser.loginAdmin("   ", PASSWORD),
                browser.loginAdmin("a".repeat(250) + "@example.com", PASSWORD),
                browser.loginAdmin(email, "x".repeat(129)));
        for (HttpResponse<String> response : refused) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(rowsOf(correlationId(response))).isEmpty();
        }
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> wrong = browser.loginAdmin(email, WRONG);
            assertThat(wrong.statusCode()).isEqualTo(401);
            assertThat(actionsOf(correlationId(wrong))).containsExactly("ADMIN_PASSWORD_LOGIN FAILURE");
        }

        HttpResponse<String> locked = browser.loginAdmin(email, PASSWORD);

        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(rowsOf(correlationId(locked))).isEmpty();
    }

    @Test
    @DisplayName("SEC-065: sem a trilha a senha certa dá 503, nenhuma sessão abre e a falha não conta no limite")
    void shouldNotOpenTheSessionWhenThePasswordAuditFails() throws Exception {
        AdminBrowser browser = browser();
        List<HttpResponse<String>> answers = withSuccessAuditFailing("ADMIN_PASSWORD_LOGIN", () -> {
            List<HttpResponse<String>> list = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                list.add(browser.loginAdmin(email, PASSWORD));
            }
            return list;
        });

        for (HttpResponse<String> answer : answers) {
            assertThat(answer.statusCode()).isEqualTo(503);
            assertThat(answer.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("JSESSIONID"));
            assertThat(rowsOf(correlationId(answer))).isEmpty();
        }
        assertThat(browser.cookie("JSESSIONID")).isNull();
        // no pending session: the code step is not reachable
        clock.advance(Duration.ofSeconds(30));
        assertThat(browser.verifyMfa(AdminMfaTestSupport.codeAt(secret, clock.instant()))
                        .statusCode())
                .isEqualTo(401);
        // once the trail is back the same password works: the six 503 were not counted as failures
        HttpResponse<String> login = browser.loginAdmin(email, PASSWORD);
        assertThat(login.statusCode()).isEqualTo(204);
        assertThat(actionsOf(correlationId(login))).containsExactly("ADMIN_PASSWORD_LOGIN SUCCESS");
    }

    @Test
    @DisplayName("SEC-065: o login inteiro grava uma linha por etapa, cada uma com o correlation ID da sua resposta")
    void shouldWriteOneRowPerStepOfTheFullLogin() throws Exception {
        AdminBrowser browser = browser();
        HttpResponse<String> password = browser.loginAdmin(email, PASSWORD);
        clock.advance(Duration.ofSeconds(30));
        HttpResponse<String> code = browser.verifyMfa(AdminMfaTestSupport.codeAt(secret, clock.instant()));
        clock.advance(Duration.ofSeconds(30));
        HttpResponse<String> regeneration = browser.post(
                "/api/admin/recovery-codes",
                "{\"code\":\"" + AdminMfaTestSupport.codeAt(secret, clock.instant()) + "\"}");

        assertThat(password.statusCode()).isEqualTo(204);
        assertThat(code.statusCode()).isEqualTo(204);
        assertThat(regeneration.statusCode()).isEqualTo(200);
        assertThat(actionsOf(correlationId(password))).containsExactly("ADMIN_PASSWORD_LOGIN SUCCESS");
        assertThat(actionsOf(correlationId(code))).containsExactly("ADMIN_MFA_LOGIN SUCCESS");
        assertThat(actionsOf(correlationId(regeneration))).containsExactly("ADMIN_RECOVERY_CODES_REGENERATED SUCCESS");
        assertThat(jdbc.queryForList(
                        "select action from audit_events where actor_id = ? order by occurred_at, action",
                        String.class,
                        adminId))
                .containsExactlyInAnyOrder(
                        "ADMIN_PASSWORD_LOGIN", "ADMIN_MFA_LOGIN", "ADMIN_RECOVERY_CODES_REGENERATED");
    }

    private UUID newAdministratorWithoutPassword() {
        jdbc.update("delete from administrators");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into administrators (id, email) values (?, ?)", id, id + "@example.com");
        return id;
    }

    private String newLink(UUID administratorId) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update(
                "insert into administrator_password_tokens (administrator_id, token_hash, expires_at)"
                        + " values (?, ?, now() + interval '1 hour')",
                administratorId,
                Tokens.hash(token));
        return token;
    }

    private HttpResponse<String> setPassword(String token) throws Exception {
        return browser().post(SETUP, "{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}");
    }

    @Test
    @DisplayName("SEC-065: definir a senha pelo link grava ADMIN_PASSWORD_SET SUCCESS; link inválido não grava")
    void shouldAuditThePasswordSet() throws Exception {
        UUID id = newAdministratorWithoutPassword();
        String token = newLink(id);

        HttpResponse<String> invalid = setPassword("link-que-nao-existe");
        HttpResponse<String> set = setPassword(token);

        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(rowsOf(correlationId(invalid))).isEmpty();
        assertThat(set.statusCode()).isEqualTo(204);
        List<Map<String, Object>> rows = rowsOf(correlationId(set));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("action", "ADMIN_PASSWORD_SET")
                .containsEntry("result", "SUCCESS")
                .containsEntry("actor_id", id)
                .containsEntry("object_type", "ADMINISTRATOR")
                .containsEntry("object_id", id);
        assertThat(rowTextOf(correlationId(set))).doesNotContain(token).doesNotContain(NEW_PASSWORD);
        // the used link again: refused and not audited
        HttpResponse<String> reused = setPassword(token);
        assertThat(reused.statusCode()).isEqualTo(400);
        assertThat(rowsOf(correlationId(reused))).isEmpty();
    }

    @Test
    @DisplayName("SEC-065: sem a trilha definir a senha dá 503, o link não é gasto e a senha não muda")
    void shouldNotConsumeTheLinkWhenThePasswordSetAuditFails() throws Exception {
        UUID id = newAdministratorWithoutPassword();
        String token = newLink(id);

        HttpResponse<String> response = withSuccessAuditFailing("ADMIN_PASSWORD_SET", () -> setPassword(token));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(rowsOf(correlationId(response))).isEmpty();
        assertThat(jdbc.queryForObject(
                        "select used_at is null from administrator_password_tokens where token_hash = ?",
                        Boolean.class,
                        Tokens.hash(token)))
                .isTrue();
        assertThat(jdbc.queryForMap("select password_hash, session_version from administrators where id = ?", id))
                .containsEntry("password_hash", null)
                .containsEntry("session_version", 0);
        // once the trail is back the same link works
        assertThat(setPassword(token).statusCode()).isEqualTo(204);
    }
}
