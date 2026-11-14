package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * A real Tomcat with the prod profile: MockMvc does not apply the container's cookie and session configuration, so
 * these checks need a real server.
 */
@Import({TestcontainersConfiguration.class, SessionServerTest.SessionRecorder.class})
@ActiveProfiles("prod")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "APP_PUBLIC_BASE_URL=http://localhost:5173",
            "RESEND_API_KEY=test-key",
            "APP_STORAGE_SUPABASE_URL=http://localhost:1",
            "APP_STORAGE_SUPABASE_SECRET_KEY=test-secret-not-real",
            "APP_ASAAS_API_BASE_URL=http://localhost:1",
            "APP_ASAAS_API_KEY=test-asaas-key-not-real"
        })
class SessionServerTest {

    private static final String PASSWORD = "senhalongaok";

    static final List<Integer> MAX_INACTIVE_SECONDS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class SessionRecorder {
        @Bean
        HttpSessionListener sessionRecorder() {
            return new HttpSessionListener() {
                @Override
                public void sessionCreated(HttpSessionEvent event) {
                    MAX_INACTIVE_SECONDS.add(event.getSession().getMaxInactiveInterval());
                }
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private HttpResponse<String> login() throws Exception {
        String email = "c" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at)"
                        + " values (gen_random_uuid(), ?, ?, now())",
                email,
                passwordEncoder.encode(PASSWORD));
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://localhost:" + port;
        HttpResponse<String> first = client.send(
                HttpRequest.newBuilder(URI.create(base + "/entrar")).build(), HttpResponse.BodyHandlers.ofString());
        String xsrf = first.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN="))
                .map(c -> c.substring("XSRF-TOKEN=".length(), c.indexOf(';')))
                .findFirst()
                .orElseThrow();
        return client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/sessions"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", "XSRF-TOKEN=" + xsrf)
                        .header("X-XSRF-TOKEN", xsrf)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String cookieValue(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(name.length() + 1, c.indexOf(';')))
                .reduce((first, last) -> last)
                .orElseThrow();
    }

    private HttpResponse<String> current(String cookieHeader) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sessions/current"));
        if (cookieHeader != null) {
            request.header("Cookie", cookieHeader);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("SEC-006: depois do logout o servidor recusa o cookie de sessão antigo")
    void shouldRefuseOldSessionCookieAfterLogoutOnRealServer() throws Exception {
        HttpResponse<String> login = login();
        String session = cookieValue(login, "JSESSIONID");
        String xsrf = cookieValue(login, "XSRF-TOKEN");
        String cookies = "JSESSIONID=" + session + "; XSRF-TOKEN=" + xsrf;
        assertThat(current(cookies).statusCode()).isEqualTo(200);

        HttpResponse<String> logout = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sessions/current"))
                                .header("Cookie", cookies)
                                .header("X-XSRF-TOKEN", xsrf)
                                .DELETE()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(current("JSESSIONID=" + session).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-007: o cookie do aparelho sai Secure, HttpOnly e SameSite=Lax em produção")
    void shouldSendSecureHttpOnlyLaxDeviceCookie() throws Exception {
        HttpResponse<String> response = login();

        assertThat(response.statusCode()).isEqualTo(204);
        String cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("SAPIENSIA_DEVICE="))
                .findFirst()
                .orElseThrow();
        assertThat(cookie).containsIgnoringCase("HttpOnly").containsIgnoringCase("Secure");
        assertThat(cookie).containsIgnoringCase("SameSite=Lax").contains("Path=/api/sessions");
    }

    @Test
    @DisplayName("SEC-005: um pedido anônimo a uma rota protegida não cria sessão nem cookie JSESSIONID")
    void shouldNotCreateSessionForAnonymousRequest() throws Exception {
        MAX_INACTIVE_SECONDS.clear();

        HttpResponse<String> response = current(null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("JSESSIONID="));
        assertThat(MAX_INACTIVE_SECONDS).isEmpty();
    }

    @Test
    @DisplayName("SEC-004: o cookie de sessão sai Secure, HttpOnly e SameSite=Lax")
    void shouldSendSecureHttpOnlyLaxSessionCookie() throws Exception {
        HttpResponse<String> response = login();

        assertThat(response.statusCode()).isEqualTo(204);
        String cookie = response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("JSESSIONID="))
                .findFirst()
                .orElseThrow();
        assertThat(cookie).containsIgnoringCase("HttpOnly").containsIgnoringCase("Secure");
        assertThat(cookie).containsIgnoringCase("SameSite=Lax");
    }

    @Test
    @DisplayName("SEC-005: a sessão real expira após 2 horas sem uso")
    void shouldExpireSessionAfterIdleTimeout() throws Exception {
        MAX_INACTIVE_SECONDS.clear();

        login();

        assertThat(MAX_INACTIVE_SECONDS).isNotEmpty().allMatch(seconds -> seconds == 7200);
    }
}
