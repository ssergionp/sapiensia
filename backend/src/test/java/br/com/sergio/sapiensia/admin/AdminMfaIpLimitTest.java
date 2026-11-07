package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

// Own context: the limiter state is in memory. The IP limit is the test default (20 attempts per window).
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.isolated-context=admin-mfa-ip-limit")
class AdminMfaIpLimitTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow
    private static final int IP_MAX_ATTEMPTS = 20;

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

    @Test
    @DisplayName("SEC-014: cada tentativa de código gasta uma ficha do IP, a mesma do login")
    void shouldSpendTheSharedIpBudgetOnEveryCodeAttempt() throws Exception {
        jdbc.update("delete from administrators");
        String email = "painel-" + UUID.randomUUID() + "@example.com";
        UUID adminId = UUID.randomUUID();
        jdbc.update(
                "insert into administrators (id, email, password_hash) values (?, ?, ?)",
                adminId,
                email,
                passwordEncoder.encode(PASSWORD));
        byte[] secret = AdminMfaTestSupport.enableMfa(jdbc, cipher, adminId);
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204); // 1 token
        for (int i = 0; i < 3; i++) {
            assertThat(browser.verifyMfa(AdminMfaTestSupport.wrongCodeAt(secret, clock.instant()))
                            .statusCode())
                    .isEqualTo(400); // 3 tokens
        }
        // the remaining tokens: if code attempts did not spend any, 3 more customer logins would still pass
        int remaining = IP_MAX_ATTEMPTS - 4;
        for (int i = 0; i < remaining; i++) {
            assertThat(browser.loginCustomer("ninguem-" + UUID.randomUUID() + "@example.com", "senha-qualquer-123")
                            .statusCode())
                    .isEqualTo(401);
        }
        assertThat(browser.loginCustomer("ninguem-" + UUID.randomUUID() + "@example.com", "senha-qualquer-123")
                        .statusCode())
                .isEqualTo(429);

        // the account has only 3 failures (not locked), yet the exhausted IP refuses even the right code
        assertThat(browser.verifyMfa(AdminMfaTestSupport.codeAt(secret, clock.instant()))
                        .statusCode())
                .isEqualTo(429);
    }
}
