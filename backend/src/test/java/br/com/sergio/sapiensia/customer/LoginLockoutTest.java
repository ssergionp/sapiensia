package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
// Own context: the limiter state is in memory, so it must not leak into or from other test classes. Every test uses
// its own e-mails and its own client IP header value, because the state persists across the methods of this class.
@SpringBootTest(
        properties = {
            "test.isolated-context=login-lockout",
            "app.login-limit.ip-max-attempts=8",
            "app.login-limit.client-ip-header=X-Test-Client-Ip"
        })
class LoginLockoutTest {

    private static final String PASSWORD = "senhalongaok";
    private static final String WRONG = "senhaerrada123";
    private static final String SESSIONS = "/api/sessions";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    MockMvc mvc;
    Cookie csrfCookie;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        csrfCookie = mvc.perform(get("/entrar")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private String newCustomer(boolean confirmed) {
        String email = "c" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at)"
                        + " values (gen_random_uuid(), ?, ?, case when ? then now() end)",
                email,
                passwordEncoder.encode(PASSWORD),
                confirmed);
        return email;
    }

    private MvcResult login(String email, String password, String ip) throws Exception {
        return mvc.perform(post(SESSIONS)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .header("X-Test-Client-Ip", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    private static void assertBlocked(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(result.getResponse().getHeaderNames()).doesNotContain("Set-Cookie");
        assertThat(result.getResponse().getCookies()).isEmpty();
        assertThat(Long.parseLong(result.getResponse().getHeader("Retry-After")))
                .isPositive();
    }

    @Test
    @DisplayName("SEC-002: shouldAnswer429AfterFiveWrongPasswordsEvenWithTheCorrectPassword")
    void shouldAnswer429AfterFiveWrongPasswordsEvenWithTheCorrectPassword() throws Exception {
        String email = newCustomer(true);
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, WRONG, "10.1.0.1").getResponse().getStatus())
                    .isEqualTo(401);
        }

        MvcResult result = login(email, PASSWORD, "10.1.0.1");

        assertBlocked(result);
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("SEC-002: shouldBlockUnknownEmailTheSameWayAsAnExistingOne")
    void shouldBlockUnknownEmailTheSameWayAsAnExistingOne() throws Exception {
        String email = newCustomer(true);
        String unknown = "ninguem-" + UUID.randomUUID() + "@example.com";
        for (int i = 0; i < 5; i++) {
            login(email, WRONG, "10.2.0.1");
        }
        for (int i = 0; i < 5; i++) {
            login(unknown, WRONG, "10.2.0.2");
        }

        MvcResult existing = login(email, WRONG, "10.2.0.1");
        MvcResult missing = login(unknown, WRONG, "10.2.0.2");

        assertBlocked(existing);
        assertBlocked(missing);
        assertThat(missing.getResponse().getStatus())
                .isEqualTo(existing.getResponse().getStatus());
        assertThat(missing.getResponse().getContentAsString())
                .isEqualTo(existing.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("SEC-002: shouldCountUnconfirmedAccountAsFailure")
    void shouldCountUnconfirmedAccountAsFailure() throws Exception {
        String email = newCustomer(false);
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, PASSWORD, "10.3.0.1").getResponse().getStatus())
                    .isEqualTo(401);
        }

        assertBlocked(login(email, PASSWORD, "10.3.0.1"));
    }

    @Test
    @DisplayName("SEC-002: shouldClearFailuresAfterSuccessfulLogin")
    void shouldClearFailuresAfterSuccessfulLogin() throws Exception {
        String email = newCustomer(true);
        // two IPs, so the per-IP limit (8) does not interfere with the 9 attempts of this test
        for (int i = 0; i < 4; i++) {
            login(email, WRONG, "10.4.0.1");
        }
        assertThat(login(email, PASSWORD, "10.4.0.1").getResponse().getStatus()).isEqualTo(204);
        for (int i = 0; i < 3; i++) {
            assertThat(login(email, WRONG, "10.4.0.2").getResponse().getStatus())
                    .isEqualTo(401);
        }

        assertThat(login(email, WRONG, "10.4.0.2").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-014: shouldAnswer429AfterIpLimitWithAFreshEmail")
    void shouldAnswer429AfterIpLimitWithAFreshEmail() throws Exception {
        for (int i = 0; i < 8; i++) {
            assertThat(login("fresh" + i + "-" + UUID.randomUUID() + "@example.com", WRONG, "10.0.0.1")
                            .getResponse()
                            .getStatus())
                    .isEqualTo(401);
        }

        assertBlocked(login("fresh-last-" + UUID.randomUUID() + "@example.com", WRONG, "10.0.0.1"));
        assertThat(login("other-" + UUID.randomUUID() + "@example.com", WRONG, "10.0.0.2")
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("shouldAnswer401WithoutHashingForTooLongInputs")
    void shouldAnswer401WithoutHashingForTooLongInputs() throws Exception {
        String longPassword = "p".repeat(129);
        String longEmail = "e".repeat(255 - "@example.com".length()) + "@example.com";
        assertThat(longEmail).hasSize(255);
        String email = newCustomer(true);

        // More than the IP limit (8): all stay 401 because such inputs never touch the limiter
        for (int i = 0; i < 9; i++) {
            MvcResult tooLongPassword = login(email, longPassword, "10.6.0.1");
            MvcResult tooLongEmail = login(longEmail, PASSWORD, "10.6.0.1");
            assertThat(tooLongPassword.getResponse().getStatus()).isEqualTo(401);
            assertThat(tooLongEmail.getResponse().getStatus()).isEqualTo(401);
            assertThat(tooLongPassword.getResponse().getContentAsString()).isEmpty();
        }
        // and they did not count as failures of the account nor spend IP tokens
        assertThat(login(email, PASSWORD, "10.6.0.1").getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("shouldNotLogEmailPasswordOrIpWhenBlocked")
    void shouldNotLogEmailPasswordOrIpWhenBlocked() throws Exception {
        String email = newCustomer(true);
        String secret = "segredoQueNaoPodeVazar";
        String ip = "10.7.7.7";
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            for (int i = 0; i < 6; i++) {
                login(email, secret, ip);
            }
        } finally {
            root.detachAppender(appender);
        }

        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage() + " " + event.getThrowableProxy();
            assertThat(text).doesNotContain(secret).doesNotContain(email).doesNotContain(ip);
        }
    }
}
