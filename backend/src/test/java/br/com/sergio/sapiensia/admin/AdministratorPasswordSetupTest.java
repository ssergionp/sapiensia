package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.customer.EmailConfirmationTokens;
import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
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

// Own context: the real XSRF-TOKEN cookie is needed, and this class owns the administrators table while it runs.
@SpringBootTest(properties = {"test.isolated-context=admin-password-setup", "app.admin.email=setup-owner@example.com"})
@Import(TestcontainersConfiguration.class)
class AdministratorPasswordSetupTest {

    private static final String NEW_PASSWORD = "senha-nova-4567"; // gitleaks:allow
    private static final String TOKEN_INVALID = "\"errors\":{\"token\":\"TOKEN_INVALID\"}";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mvc;
    private Cookie csrfCookie;
    private UUID adminId;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        csrfCookie = mvc.perform(get("/painel/definir-senha"))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        // the startup bootstrap may have created one: this test controls the only Administrator
        jdbc.update("delete from administrators");
        adminId = UUID.randomUUID();
        jdbc.update("insert into administrators (id, email) values (?, ?)", adminId, adminId + "@example.com");
        token = newToken();
        insertLink(token, "1 hour");
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void insertLink(String linkToken, String validFor) {
        jdbc.update(
                "insert into administrator_password_tokens (administrator_id, token_hash, expires_at)"
                        + " values (?, ?, now() + cast(? as interval))",
                adminId,
                EmailConfirmationTokens.hash(linkToken),
                validFor);
    }

    private MvcResult setPassword(String linkToken, String newPassword) throws Exception {
        return mvc.perform(post("/api/admin/password-setup")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + linkToken + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andReturn();
    }

    private String storedHash() {
        return jdbc.queryForObject("select password_hash from administrators where id = ?", String.class, adminId);
    }

    private boolean linkStillValid() {
        return jdbc.queryForObject(
                        "select count(*) from administrator_password_tokens"
                                + " where administrator_id = ? and used_at is null",
                        Long.class,
                        adminId)
                == 1;
    }

    private void assertRejectedKeepingTheLink(String password, String code) throws Exception {
        MvcResult result = setPassword(token, password);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("\"errors\":{\"newPassword\":\"" + code + "\"}");
        assertThat(linkStillValid()).isTrue();
        assertThat(storedHash()).isNull();
    }

    @Test
    @DisplayName("SEC-007 / SEC-006: link válido define a senha com 204 e o hash confere")
    void shouldSetThePasswordWithAValidLink() throws Exception {
        MvcResult result = setPassword(token, NEW_PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(storedHash()).startsWith("$argon2id$");
        assertThat(passwordEncoder.matches(NEW_PASSWORD, storedHash())).isTrue();
        assertThat(jdbc.queryForObject(
                        "select session_version from administrators where id = ?", Integer.class, adminId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-007: reuso, link vencido e link desconhecido dão o mesmo 400 TOKEN_INVALID")
    void shouldAnswerTheSameForUsedExpiredAndUnknownLinks() throws Exception {
        assertThat(setPassword(token, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);
        MvcResult reused = setPassword(token, "outra-senha-9876");

        String expiredToken = newToken();
        insertLink(expiredToken, "-1 second");
        MvcResult expired = setPassword(expiredToken, NEW_PASSWORD);
        MvcResult unknown = setPassword(newToken(), NEW_PASSWORD);

        for (MvcResult result : List.of(reused, expired, unknown)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString())
                    .contains(TOKEN_INVALID)
                    .isEqualTo(reused.getResponse().getContentAsString());
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
    @DisplayName("SEC-007 / SEC-002: senha vazada é recusada e o link continua válido")
    void shouldRejectACompromisedPasswordKeepingTheLink() throws Exception {
        assertRejectedKeepingTheLink("Password123456", "PASSWORD_COMPROMISED");
    }

    @Test
    @DisplayName("SEC-007: duas requisições em paralelo com o mesmo link dão exatamente um 204")
    void shouldLetOnlyOneParallelRequestWin() throws Exception {
        int requests = 2;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                String password = "senha-paralela-" + i + "-1234";
                results.add(pool.submit(() -> {
                    start.await();
                    return setPassword(token, password).getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(204, 400);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("SEC-007: sem o token CSRF a definição de senha é recusada com 403 e o link continua válido")
    void shouldRejectARequestWithoutTheCsrfToken() throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/password-setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(linkStillValid()).isTrue();
    }

    @Test
    @DisplayName("SEC-007: o token só vale no corpo do POST; GET com o token na query responde 401 ao anônimo")
    void shouldNotAcceptTheTokenInAGetRequest() throws Exception {
        MvcResult result = mvc.perform(get("/api/admin/password-setup").queryParam("token", token))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(linkStillValid()).isTrue();
        assertThat(storedHash()).isNull();
    }
}
