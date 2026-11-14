package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.product.LocalDownloadFixtures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** The full prod profile on a real server (Secure cookies are sent by hand, as SessionServerTest does). */
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("prod")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=local-download-prod",
            "APP_PUBLIC_BASE_URL=http://localhost:5173",
            "RESEND_API_KEY=test-key",
            "APP_STORAGE_SUPABASE_URL=http://localhost:1",
            "APP_STORAGE_SUPABASE_SECRET_KEY=test-secret-not-real",
            "APP_ASAAS_API_BASE_URL=http://localhost:1",
            "APP_ASAAS_API_KEY=test-asaas-key-not-real"
        })
class LocalDownloadProdTest {

    private static final String PASSWORD = "senhalongaok"; // gitleaks:allow

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    ApplicationContext context;

    private HttpResponse<String> get(String path, String cookies) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (cookies != null) {
            request.header("Cookie", cookies);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(name.length() + 1, c.indexOf(';')))
                .reduce((first, last) -> last)
                .orElseThrow();
    }

    private String customerCookies() throws Exception {
        String email = "c" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at)"
                        + " values (gen_random_uuid(), ?, ?, now())",
                email,
                encoder.encode(PASSWORD));
        String xsrf = cookie(get("/entrar", null), "XSRF-TOKEN");
        HttpResponse<String> login = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sessions"))
                                .header("Content-Type", "application/json")
                                .header("Cookie", "XSRF-TOKEN=" + xsrf)
                                .header("X-XSRF-TOKEN", xsrf)
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(204);
        return "JSESSIONID=" + cookie(login, "JSESSIONID") + "; XSRF-TOKEN=" + cookie(login, "XSRF-TOKEN");
    }

    @Test
    @DisplayName("SEC-010: no perfil prod a rota do link local não existe")
    void shouldNotHaveTheLocalDownloadRouteInProd() throws Exception {
        assertThat(LocalDownloadFixtures.hasLocalDownloadController(context)).isFalse();
        String path = "/api/local-downloads/" + "x".repeat(43);

        HttpResponse<String> anonymous = get(path, null);
        HttpResponse<String> customer = get(path, customerCookies());

        assertThat(anonymous.statusCode()).isEqualTo(404);
        assertThat(anonymous.body()).isEmpty();
        assertThat(customer.statusCode()).isEqualTo(404);
        assertThat(customer.body()).isEmpty();
    }
}
