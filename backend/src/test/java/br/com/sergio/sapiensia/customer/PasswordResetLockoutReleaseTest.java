package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
// Own context: the limiter state is in memory and this class needs the real XSRF-TOKEN cookie. Every test uses its own
// e-mail and its own client IP header value, because the limiter state persists across the methods of this class.
@SpringBootTest(
        properties = {
            "test.isolated-context=password-reset-lockout-release",
            "app.login-limit.ip-max-attempts=8",
            "app.login-limit.client-ip-header=X-Test-Client-Ip"
        })
class PasswordResetLockoutReleaseTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final int IP_LIMIT = 8;
    private static final String OLD_PASSWORD = "senha-antiga-123";
    private static final String NEW_PASSWORD = "senha-nova-4567"; // gitleaks:allow
    private static final String WRONG = "senhaerrada123";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RecordingEmailSender sender;

    @Value("${app.public-base-url}")
    private String baseUrl;

    private MockMvc mvc;
    private Cookie csrfCookie;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        csrfCookie = mvc.perform(get("/entrar")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private String newConfirmedCustomer() {
        String email = "unlock-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at) values (gen_random_uuid(), ?, ?,"
                        + " now())",
                email,
                passwordEncoder.encode(OLD_PASSWORD));
        return email;
    }

    private int login(String email, String password, String ip) throws Exception {
        return mvc.perform(post("/api/sessions")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .header("X-Test-Client-Ip", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private void lockAccount(String email, String ip) throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, WRONG, ip)).isEqualTo(401);
        }
        assertThat(login(email, OLD_PASSWORD, ip))
                .as("locked even with the right password")
                .isEqualTo(429);
    }

    /** Asks for the link through the API and returns the token of the e-mailed link. */
    private String requestResetLink(String email) throws Exception {
        assertThat(mvc.perform(post("/api/customers/password-resets")
                                .cookie(csrfCookie)
                                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + email + "\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(202);
        String text = sender.awaitMessageTo(email, WAIT).orElseThrow().text();
        Matcher matcher = Pattern.compile(Pattern.quote(baseUrl + "/redefinir-senha#") + "([A-Za-z0-9_-]{43})")
                .matcher(text);
        assertThat(matcher.find()).as("reset link in the e-mail").isTrue();
        return matcher.group(1);
    }

    private int resetPassword(String token, String newPassword) throws Exception {
        return mvc.perform(post("/api/customers/password-resets/confirmation")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    @DisplayName("SEC-002 SEC-007: a troca de senha pelo link libera o bloqueio da conta")
    void shouldReleaseTheAccountLockoutAfterAPasswordReset() throws Exception {
        String email = newConfirmedCustomer();
        lockAccount(email, "10.30.0.1");

        assertThat(resetPassword(requestResetLink(email), NEW_PASSWORD)).isEqualTo(204);

        assertThat(login(email, NEW_PASSWORD, "10.30.0.1")).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-002: a troca de senha pelo link não libera o limite por IP")
    void shouldKeepTheIpLimitAfterAPasswordReset() throws Exception {
        String email = newConfirmedCustomer();
        String ip = "10.31.0.1";
        for (int i = 0; i < IP_LIMIT; i++) {
            assertThat(login("ip-" + UUID.randomUUID() + "@example.com", WRONG, ip))
                    .isEqualTo(401);
        }
        assertThat(login(email, OLD_PASSWORD, ip)).isEqualTo(429);

        assertThat(resetPassword(requestResetLink(email), NEW_PASSWORD)).isEqualTo(204);

        assertThat(login(email, NEW_PASSWORD, ip)).isEqualTo(429);
        // the account itself is free: the same login from another IP works
        assertThat(login(email, NEW_PASSWORD, "10.31.0.2")).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-002: uma troca recusada (link inválido) não libera o bloqueio da conta")
    void shouldKeepTheAccountLockoutWhenTheResetFails() throws Exception {
        String email = newConfirmedCustomer();
        lockAccount(email, "10.32.0.1");

        assertThat(resetPassword("x".repeat(43), NEW_PASSWORD)).isEqualTo(400);

        assertThat(login(email, OLD_PASSWORD, "10.32.0.1")).isEqualTo(429);
    }
}
