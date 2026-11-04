package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A real server, so every login is a separate container session, like two devices. Own context: a test that uses
 * the csrf() shortcut replaces the CSRF repository of a shared context, and these tests need the real XSRF-TOKEN
 * cookie.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.isolated-context=session-revocation")
class SessionRevocationTest {

    private static final String OLD_PASSWORD = "senha-antiga-123";
    private static final String NEW_PASSWORD = "senha-nova-4567";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    PasswordResetTokens tokens;

    @Autowired
    CustomerUserDetailsService userDetailsService;

    @Autowired
    WebApplicationContext context;

    private final HttpClient client = HttpClient.newHttpClient();
    private String email;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        email = "revoke-" + UUID.randomUUID() + "@example.com";
        customerId = UUID.randomUUID();
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at) values (?, ?, ?, now())",
                customerId,
                email,
                passwordEncoder.encode(OLD_PASSWORD));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static String cookieValue(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(name.length() + 1, c.indexOf(';')))
                .reduce((first, last) -> last)
                .orElseThrow();
    }

    private String xsrfToken() throws Exception {
        HttpResponse<String> page = client.send(
                HttpRequest.newBuilder(URI.create(url("/entrar"))).build(), HttpResponse.BodyHandlers.ofString());
        return cookieValue(page, "XSRF-TOKEN");
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        String xsrf = xsrfToken();
        return client.send(
                HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Content-Type", "application/json")
                        .header("Cookie", "XSRF-TOKEN=" + xsrf)
                        .header("X-XSRF-TOKEN", xsrf)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Logs in like a new device and returns its session cookie. */
    private String login(String password) throws Exception {
        HttpResponse<String> response =
                postJson("/api/sessions", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertThat(response.statusCode()).isEqualTo(204);
        return "JSESSIONID=" + cookieValue(response, "JSESSIONID");
    }

    private void resetPassword() throws Exception {
        String token = tokens.issue(customerId);
        HttpResponse<String> response = postJson(
                "/api/customers/password-resets/confirmation",
                "{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}");
        assertThat(response.statusCode()).isEqualTo(204);
    }

    private int current(String sessionCookie) throws Exception {
        return client.send(
                        HttpRequest.newBuilder(URI.create(url("/api/sessions/current")))
                                .header("Cookie", sessionCookie)
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    @Test
    @DisplayName("SEC-006: depois da troca de senha, cada sessão aberta antes recebe 401")
    void shouldEndEverySessionWhenThePasswordIsReset() throws Exception {
        String phone = login(OLD_PASSWORD);
        String laptop = login(OLD_PASSWORD);
        assertThat(current(phone)).isEqualTo(200);
        assertThat(current(laptop)).isEqualTo(200);

        resetPassword();

        assertThat(current(phone)).isEqualTo(401);
        assertThat(current(laptop)).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-006: depois da troca, um novo login com a senha nova abre uma sessão válida")
    void shouldAcceptANewLoginAfterTheReset() throws Exception {
        login(OLD_PASSWORD);
        resetPassword();

        String session = login(NEW_PASSWORD);

        assertThat(current(session)).isEqualTo(200);
        assertThat(current(session)).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-006: sem troca de senha a sessão continua valendo e a rota pública segue aberta sem sessão")
    void shouldKeepAGoodSessionAndThePublicRoutes() throws Exception {
        String session = login(OLD_PASSWORD);

        assertThat(current(session)).isEqualTo(200);
        assertThat(current(session)).isEqualTo(200);
        int health = client.send(
                        HttpRequest.newBuilder(URI.create(url("/api/system/health")))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .statusCode();
        assertThat(health).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-006: login que leu a conta antes da troca e gravou a sessão depois dela recebe 401")
    void shouldRefuseASessionSavedWithTheVersionReadBeforeTheReset() throws Exception {
        // the login has read the account (and its version) but has not saved the session yet
        var stale = ((CustomerUserDetailsService.CustomerPrincipal) userDetailsService.loadUserByUsername(email))
                .withoutPassword();
        resetPassword();
        // now the login saves its session, built from what it read before the reset
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(stale, null, stale.getAuthorities()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        int status = mvc.perform(get("/api/sessions/current").session(session))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
    }
}
