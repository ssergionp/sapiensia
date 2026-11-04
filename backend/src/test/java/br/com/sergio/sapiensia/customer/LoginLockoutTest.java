package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

    // Spy (not a replacement): lets the tests prove that a blocked or rejected login never hashes the password
    @MockitoSpyBean
    PasswordEncoder passwordEncoder;

    // Spy on the user lookup too, to simulate the database going down in the middle of a login
    @MockitoSpyBean
    CustomerUserDetailsService userDetailsService;

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
    @DisplayName("SEC-002: um login bloqueado não calcula o hash")
    void shouldNotHashPasswordWhenLoginIsBlocked() throws Exception {
        String email = newCustomer(true);
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, WRONG, "10.8.0.1").getResponse().getStatus())
                    .isEqualTo(401);
        }
        clearInvocations(passwordEncoder);

        MvcResult result = login(email, PASSWORD, "10.8.0.1");

        assertBlocked(result);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    @DisplayName("SEC-002: parallel wrong passwords for one e-mail reach the hash at most five times")
    void shouldLetOnlyFiveParallelAttemptsReachTheHashForOneEmail() throws Exception {
        String email = newCustomer(true);
        clearInvocations(passwordEncoder);
        int threads = 8; // the IP limit of this context; one IP per thread so only the account limit is in play
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String ip = "10.9.0." + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return login(email, WRONG, ip).getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses.stream().filter(status -> status == 401).count()).isEqualTo(5);
        assertThat(statuses.stream().filter(status -> status == 429).count()).isEqualTo(3);
        verify(passwordEncoder, times(5)).matches(any(), any());
    }

    @Test
    @DisplayName("SEC-002: an unexpected exception while checking the password leaves no failure counted")
    void shouldNotCountAFailureWhenAnUnexpectedExceptionInterruptsTheLogin() throws Exception {
        String email = newCustomer(true);
        doThrow(new IllegalStateException("boom"))
                .doCallRealMethod()
                .when(passwordEncoder)
                .matches(any(), any());

        assertThatThrownBy(() -> login(email, WRONG, "10.10.0.1")).hasRootCauseInstanceOf(IllegalStateException.class);

        // five more wrong passwords are still five attempts, not six: no slot was left behind
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, WRONG, "10.10.0.1").getResponse().getStatus())
                    .isEqualTo(401);
        }
        assertBlocked(login(email, WRONG, "10.10.0.1"));
    }

    @Test
    @DisplayName("SEC-002: a failure of the user lookup (database down) is not counted as a wrong password")
    void shouldNotCountAFailureWhenTheUserLookupBreaks() throws Exception {
        String email = newCustomer(true);
        // Spring Security wraps this in InternalAuthenticationServiceException, which is an AuthenticationException
        doThrow(new DataAccessResourceFailureException("database down"))
                .doCallRealMethod()
                .when(userDetailsService)
                .loadUserByUsername(any());

        // not a 401: the customer's password was never checked, so the answer must not say it was wrong
        MvcResult unavailable = login(email, PASSWORD, "10.11.0.1");
        assertThat(unavailable.getResponse().getStatus()).isEqualTo(503);
        assertThat(unavailable.getResponse().getContentAsString()).isEmpty();
        assertThat(unavailable.getResponse().getCookies()).isEmpty();

        // the customer did nothing wrong: five wrong passwords are still five attempts, the sixth is blocked
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, WRONG, "10.11.0.1").getResponse().getStatus())
                    .isEqualTo(401);
        }
        assertBlocked(login(email, WRONG, "10.11.0.1"));
    }

    @Test
    @DisplayName("SEC-002: shouldBlockUnknownEmailTheSameWayAsAnExistingOne")
    void shouldBlockUnknownEmailTheSameWayAsAnExistingOne() throws Exception {
        String email = newCustomer(true);
        String unknown = "ninguem-" + UUID.randomUUID() + "@example.com";
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, WRONG, "10.2.0.1").getResponse().getStatus())
                    .isEqualTo(401);
        }
        for (int i = 0; i < 5; i++) {
            assertThat(login(unknown, WRONG, "10.2.0.2").getResponse().getStatus())
                    .isEqualTo(401);
        }

        MvcResult existing = login(email, WRONG, "10.2.0.1");
        MvcResult missing = login(unknown, WRONG, "10.2.0.2");

        assertBlocked(existing);
        assertBlocked(missing);
        assertThat(missing.getResponse().getHeaderNames())
                .containsExactlyInAnyOrderElementsOf(existing.getResponse().getHeaderNames());
        assertThat(Long.parseLong(existing.getResponse().getHeader("Retry-After")))
                .isBetween(1L, 900L);
        assertThat(Long.parseLong(missing.getResponse().getHeader("Retry-After")))
                .isBetween(1L, 900L);
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
            assertThat(login(email, WRONG, "10.4.0.1").getResponse().getStatus())
                    .isEqualTo(401);
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
        clearInvocations(passwordEncoder);

        // More than the IP limit (8): all stay 401 because such inputs never touch the limiter
        for (int i = 0; i < 9; i++) {
            MvcResult tooLongPassword = login(email, longPassword, "10.6.0.1");
            MvcResult tooLongEmail = login(longEmail, PASSWORD, "10.6.0.1");
            assertThat(tooLongPassword.getResponse().getStatus()).isEqualTo(401);
            assertThat(tooLongEmail.getResponse().getStatus()).isEqualTo(401);
            assertThat(tooLongPassword.getResponse().getContentAsString()).isEmpty();
        }
        // too-long inputs never reach the password hasher
        verify(passwordEncoder, never()).matches(any(), any());
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
