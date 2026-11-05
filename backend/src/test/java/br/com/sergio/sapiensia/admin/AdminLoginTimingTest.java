package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

// Own context with generous limits: 26 failed logins per case must not lock the account or hit the IP limit.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=admin-login-timing",
            "app.login-limit.account-max-failures=1000",
            "app.login-limit.ip-max-attempts=1000"
        })
class AdminLoginTimingTest {

    private static final String WRONG = "senha-errada-45678";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    @Test
    @DisplayName("SEC-010 / SEC-002: o tempo do login não revela se o e-mail existe nem se a senha foi definida")
    void shouldTakeTheSameTimeForTheThreeFailures() throws Exception {
        jdbc.update("delete from administrators");
        String known = "timing-" + UUID.randomUUID() + "@example.com";
        String noPassword = "timing-sem-" + UUID.randomUUID() + "@example.com";
        String unknown = "timing-nao-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into administrators (email, password_hash) values (?, ?)",
                known,
                passwordEncoder.encode("senha-do-painel-123"));
        jdbc.update("insert into administrators (email) values (?)", noPassword);
        AdminBrowser browser = new AdminBrowser(port);
        List<String> cases = List.of(known, noPassword, unknown);

        for (int i = 0; i < 6; i++) {
            browser.loginAdmin(cases.get(i % 3), WRONG);
        }
        List<List<Long>> times = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (int i = 0; i < 21; i++) {
            int which = i % 3;
            long start = System.nanoTime();
            int status = browser.loginAdmin(cases.get(which), WRONG).statusCode();
            times.get(which).add((System.nanoTime() - start) / 1_000_000);
            assertThat(status).isEqualTo(401);
        }

        long knownMedian = median(times.get(0));
        long noPasswordMedian = median(times.get(1));
        long unknownMedian = median(times.get(2));
        assertThat(Math.abs(knownMedian - unknownMedian)).isLessThan(50);
        assertThat(Math.abs(knownMedian - noPasswordMedian)).isLessThan(50);
        assertThat(Math.abs(noPasswordMedian - unknownMedian)).isLessThan(50);
    }
}
