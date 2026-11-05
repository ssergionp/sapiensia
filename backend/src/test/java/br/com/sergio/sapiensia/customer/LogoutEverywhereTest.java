package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A real server, so every login is a separate container session, like two devices. Own context for the same reason
 * as SessionRevocationTest: these tests need the real XSRF-TOKEN cookie.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.isolated-context=logout-everywhere")
class LogoutEverywhereTest {

    private static final String PASSWORD = "senha-antiga-123"; // gitleaks:allow

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private final HttpClient client = HttpClient.newHttpClient();
    private String email;

    @BeforeEach
    void setUp() {
        email = createCustomer();
    }

    private String createCustomer() {
        String address = "everywhere-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at) values (?, ?, ?, now())",
                UUID.randomUUID(),
                address,
                passwordEncoder.encode(PASSWORD));
        return address;
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

    /** Logs in like a new device and returns its session cookie. */
    private String login(String address) throws Exception {
        return "JSESSIONID=" + cookieValue(loginResponse(address), "JSESSIONID");
    }

    private static String setCookieHeader(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .reduce((first, last) -> last)
                .orElseThrow();
    }

    private static List<String> attributes(String setCookie) {
        return List.of(setCookie.split("; "));
    }

    private HttpResponse<String> loginResponse(String address) throws Exception {
        String xsrf = xsrfToken();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/sessions")))
                        .header("Content-Type", "application/json")
                        .header("Cookie", "XSRF-TOKEN=" + xsrf)
                        .header("X-XSRF-TOKEN", xsrf)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"" + address + "\",\"password\":\"" + PASSWORD + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(204);
        return response;
    }

    private int current(String sessionCookie) throws Exception {
        return client.send(
                        HttpRequest.newBuilder(URI.create(url("/api/sessions/current")))
                                .header("Cookie", sessionCookie)
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .statusCode();
    }

    private HttpResponse<String> logoutEverywhere(String cookies, String xsrf) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(url("/api/sessions"))).DELETE();
        if (cookies != null) {
            request.header("Cookie", cookies);
        }
        if (xsrf != null) {
            request.header("X-XSRF-TOKEN", xsrf);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> logoutEverywhereWithToken(String session) throws Exception {
        String xsrf = xsrfToken();
        return logoutEverywhere(session + "; XSRF-TOKEN=" + xsrf, xsrf);
    }

    @Test
    @DisplayName("SEC-009 e SEC-006: sair de todos os aparelhos encerra cada sessão da conta com 204")
    void shouldEndEverySessionOfTheAccount() throws Exception {
        String phone = login(email);
        String laptop = login(email);
        assertThat(current(phone)).isEqualTo(200);
        assertThat(current(laptop)).isEqualTo(200);

        HttpResponse<String> response = logoutEverywhereWithToken(laptop);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().allValues("Set-Cookie"))
                .anyMatch(c -> c.startsWith("JSESSIONID=;") && c.contains("Expires=Thu, 01 Jan 1970"));
        assertThat(current(phone)).isEqualTo(401);
        assertThat(current(laptop)).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-009: o cookie de sessão é apagado como no logout (#10) e com o mesmo Path do cookie original")
    void shouldClearTheSessionCookieLikeTheLogoutDoes() throws Exception {
        HttpResponse<String> loginResponse = loginResponse(email);
        String original = setCookieHeader(loginResponse, "JSESSIONID");
        String originalPath = attributes(original).stream()
                .filter(a -> a.startsWith("Path="))
                .findFirst()
                .orElseThrow();
        String xsrf = xsrfToken();
        HttpResponse<String> plainLogout = client.send(
                HttpRequest.newBuilder(URI.create(url("/api/sessions/current")))
                        .DELETE()
                        .header(
                                "Cookie",
                                "JSESSIONID=" + cookieValue(loginResponse, "JSESSIONID") + "; XSRF-TOKEN=" + xsrf)
                        .header("X-XSRF-TOKEN", xsrf)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(plainLogout.statusCode()).isEqualTo(204);

        HttpResponse<String> everywhere = logoutEverywhereWithToken(login(email));

        List<String> cleared = attributes(setCookieHeader(everywhere, "JSESSIONID"));
        assertThat(cleared).isEqualTo(attributes(setCookieHeader(plainLogout, "JSESSIONID")));
        assertThat(cleared).contains(originalPath).anyMatch(a -> a.startsWith("Expires=Thu, 01 Jan 1970"));
    }

    @Test
    @DisplayName("SEC-009 e SEC-006: depois de sair de todos os aparelhos, um login novo abre uma sessão válida")
    void shouldAcceptANewLoginAfterLoggingOutEverywhere() throws Exception {
        String old = login(email);
        logoutEverywhereWithToken(old);

        String session = login(email);

        assertThat(current(session)).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-009: sair de todos os aparelhos não encerra a sessão de outro Cliente")
    void shouldKeepTheSessionOfAnotherCustomer() throws Exception {
        String other = login(createCustomer());
        String mine = login(email);

        logoutEverywhereWithToken(mine);

        assertThat(current(other)).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-009: sem sessão a resposta é 401")
    void shouldRefuseAnonymousLogoutEverywhere() throws Exception {
        String xsrf = xsrfToken();

        HttpResponse<String> response = logoutEverywhere("XSRF-TOKEN=" + xsrf, xsrf);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-009: sem o token CSRF a resposta é 403 e as sessões continuam valendo")
    void shouldRefuseLogoutEverywhereWithoutCsrfToken() throws Exception {
        String phone = login(email);

        HttpResponse<String> response = logoutEverywhere(phone, null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(current(phone)).isEqualTo(200);
    }
}
