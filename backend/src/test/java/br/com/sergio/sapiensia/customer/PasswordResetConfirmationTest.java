package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.email.EmailMessage;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

// Own context: a test that uses the csrf() shortcut replaces the CSRF repository of the shared context for good, and
// this class needs the real XSRF-TOKEN cookie.
@SpringBootTest(properties = "test.isolated-context=password-reset-confirmation")
@Import(TestcontainersConfiguration.class)
class PasswordResetConfirmationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Duration NO_MAIL_WAIT = Duration.ofSeconds(2);
    private static final String OLD_PASSWORD = "senha-antiga-123";
    private static final String NEW_PASSWORD = "senha-nova-4567";
    private static final String TOKEN_INVALID = "\"errors\":{\"token\":\"TOKEN_INVALID\"}";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RecordingEmailSender sender;

    @Autowired
    private PasswordResetTokens tokens;

    private MockMvc mvc;
    private Cookie csrfCookie;
    private String email;
    private UUID customerId;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        csrfCookie = mvc.perform(get("/cadastro")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        email = "confirm-" + UUID.randomUUID() + "@example.com";
        customerId = UUID.randomUUID();
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at) values (?, ?, ?, now())",
                customerId,
                email,
                passwordEncoder.encode(OLD_PASSWORD));
        token = tokens.issue(customerId);
    }

    private MvcResult confirm(String linkToken, String newPassword) throws Exception {
        return mvc.perform(post("/api/customers/password-resets/confirmation")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + linkToken + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andReturn();
    }

    private int login(String password) throws Exception {
        return mvc.perform(post("/api/sessions")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String storedHash() {
        return jdbc.queryForObject("select password_hash from customers where id = ?", String.class, customerId);
    }

    private boolean linkStillValid() {
        return jdbc.queryForObject(
                        "select count(*) from password_reset_tokens where customer_id = ? and used_at is null",
                        Long.class,
                        customerId)
                == 1;
    }

    private void assertRejectedKeepingTheLink(String password, String code) throws Exception {
        MvcResult result = confirm(token, password);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("\"errors\":{\"newPassword\":\"" + code + "\"}");
        assertThat(linkStillValid()).isTrue();
        assertThat(passwordEncoder.matches(OLD_PASSWORD, storedHash())).isTrue();
    }

    @Test
    @DisplayName("SEC-007: link válido troca a senha com 204, sem cookie de sessão")
    void shouldChangeThePasswordWithoutLoggingIn() throws Exception {
        MvcResult result = confirm(token, NEW_PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.contains("JSESSIONID"));
        assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
        assertThat(login(NEW_PASSWORD)).isEqualTo(204);
        assertThat(login(OLD_PASSWORD)).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-007: o titular recebe o aviso da troca, sem senha nem token")
    void shouldSendTheNoticeWithoutPasswordOrToken() throws Exception {
        confirm(token, NEW_PASSWORD);

        EmailMessage notice = sender.awaitMessageTo(email, WAIT).orElseThrow();
        assertThat(notice.subject()).isEqualTo("Sua senha na SapiensIA foi alterada");
        assertThat(notice.text())
                .contains("Se não foi você, use \"Esqueci minha senha\" para criar outra senha agora.")
                .doesNotContain(NEW_PASSWORD)
                .doesNotContain(OLD_PASSWORD)
                .doesNotContain(token)
                .doesNotContain("http");
    }

    @Test
    @DisplayName("SEC-007: dois cliques simultâneos com o mesmo link: só um troca a senha")
    void shouldLetOnlyOneOfTwoParallelRequestsChangeThePassword() throws Exception {
        String first = "primeira-senha-123";
        String second = "segunda-senha-4567";
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> a = pool.submit(() -> {
                start.await();
                return confirm(token, first);
            });
            Future<MvcResult> b = pool.submit(() -> {
                start.await();
                return confirm(token, second);
            });
            start.countDown();
            MvcResult resultA = a.get();
            MvcResult resultB = b.get();

            List<Integer> statuses = List.of(
                    resultA.getResponse().getStatus(), resultB.getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(204, 400);
            MvcResult loser = resultA.getResponse().getStatus() == 400 ? resultA : resultB;
            assertThat(loser.getResponse().getContentAsString()).contains(TOKEN_INVALID);
            String winnerPassword = resultA.getResponse().getStatus() == 204 ? first : second;
            assertThat(passwordEncoder.matches(winnerPassword, storedHash())).isTrue();
        }
    }

    @Test
    @DisplayName("SEC-007: link usado, vencido e inexistente dão a mesma resposta 400")
    void shouldAnswerTheSameForUsedExpiredAndUnknownLinks() throws Exception {
        confirm(token, NEW_PASSWORD);
        MvcResult used = confirm(token, "outra-senha-9876");

        String expiredToken = tokens.issue(customerId);
        jdbc.update(
                "update password_reset_tokens set expires_at = now() - interval '1 minute' where token_hash = ?",
                EmailConfirmationTokens.hash(expiredToken));
        MvcResult expired = confirm(expiredToken, "outra-senha-9876");

        MvcResult unknown = confirm("x".repeat(43), "outra-senha-9876");

        for (MvcResult result : List.of(used, expired, unknown)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString())
                    .contains(TOKEN_INVALID)
                    .isEqualTo(used.getResponse().getContentAsString());
        }
        assertThat(passwordEncoder.matches(NEW_PASSWORD, storedHash())).isTrue();
    }

    @Test
    @DisplayName("SEC-007: senha curta demais é recusada e o link continua válido")
    void shouldRejectATooShortPasswordKeepingTheLink() throws Exception {
        assertRejectedKeepingTheLink("a".repeat(11), "PASSWORD_TOO_SHORT");
    }

    @Test
    @DisplayName("SEC-007: senha longa demais é recusada e o link continua válido")
    void shouldRejectATooLongPasswordKeepingTheLink() throws Exception {
        assertRejectedKeepingTheLink("a".repeat(129), "PASSWORD_TOO_LONG");
    }

    @Test
    @DisplayName("SEC-007: senha vazada é recusada e o link continua válido")
    void shouldRejectACompromisedPasswordKeepingTheLink() throws Exception {
        assertRejectedKeepingTheLink("Password123456", "PASSWORD_COMPROMISED");
    }

    @Test
    @DisplayName("SEC-007: a senha é gravada em Argon2id e os espaços nas pontas não são aparados")
    void shouldStoreArgon2idWithoutTrimmingThePassword() throws Exception {
        String spaced = "  senha com espacos  ";

        assertThat(confirm(token, spaced).getResponse().getStatus()).isEqualTo(204);

        assertThat(storedHash()).startsWith("$argon2id$");
        assertThat(passwordEncoder.matches(spaced, storedHash())).isTrue();
        assertThat(passwordEncoder.matches(spaced.trim(), storedHash())).isFalse();
    }

    @Test
    @DisplayName("SEC-007: sem o token CSRF a troca é recusada com 403")
    void shouldRejectARequestWithoutTheCsrfToken() throws Exception {
        MvcResult result = mvc.perform(post("/api/customers/password-resets/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(linkStillValid()).isTrue();
        assertThat(sender.awaitMessageTo(email, NO_MAIL_WAIT)).isEmpty();
    }
}
