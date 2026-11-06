package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import br.com.sergio.sapiensia.security.Totp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A TOTP code is accepted once: the last accepted step only moves forward, in one conditional UPDATE. */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-mfa-replay", "app.login-limit.ip-max-attempts=1000"})
class AdminMfaReplayTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow

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

    private AdminBrowser pendingBrowser() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
        return browser;
    }

    @Test
    @DisplayName("SEC-001: o mesmo código usado em outra sessão pendente dá 400")
    void shouldRefuseACodeUsedOnce() throws Exception {
        String code = AdminMfaTestSupport.codeAt(secret, clock.instant());
        AdminBrowser first = pendingBrowser();
        AdminBrowser second = pendingBrowser();

        assertThat(first.verifyMfa(code).statusCode()).isEqualTo(204);

        assertThat(second.verifyMfa(code).statusCode()).isEqualTo(400);
        assertThat(second.get("/api/admin/sessions/current").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-001: duas requisições paralelas com o mesmo código válido: exatamente um 204")
    void shouldAcceptOnlyOneOfTwoParallelRequestsWithTheSameCode() throws Exception {
        String code = AdminMfaTestSupport.codeAt(secret, clock.instant());
        long step = Totp.stepAt(clock.instant());
        List<AdminBrowser> browsers = List.of(pendingBrowser(), pendingBrowser());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (AdminBrowser browser : browsers) {
                results.add(pool.submit(() -> {
                    start.await();
                    return browser.verifyMfa(code).statusCode();
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
        assertThat(jdbc.queryForObject("select totp_last_step from administrators where id = ?", Long.class, adminId))
                .isEqualTo(step);
    }

    @Test
    @DisplayName("SEC-001: acceptStep só grava um passo maior que o último aceito")
    void shouldOnlyMoveTheLastStepForward() {
        long n = 1_000_000;

        assertThat(administrators.acceptStep(adminId, n)).isEqualTo(1);
        assertThat(administrators.acceptStep(adminId, n)).isZero();
        assertThat(administrators.acceptStep(adminId, n - 1)).isZero();
        assertThat(administrators.acceptStep(adminId, n + 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-001: um segredo pendente (MFA não ativado) nunca aceita um passo")
    void shouldNeverAcceptAStepForAPendingSecret() {
        jdbc.update("update administrators set totp_enabled_at = null where id = ?", adminId);

        assertThat(administrators.acceptStep(adminId, 1_000_000)).isZero();
        assertThat(administrators.findTotp(adminId))
                .hasValueSatisfying(totp -> assertThat(totp.enabled()).isFalse());
    }
}
