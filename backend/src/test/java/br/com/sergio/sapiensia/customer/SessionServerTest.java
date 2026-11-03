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
        properties = {"APP_PUBLIC_BASE_URL=http://localhost:5173", "RESEND_API_KEY=test-key"})
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
