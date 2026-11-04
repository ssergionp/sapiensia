package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A failure while registering the device or scheduling the notice must not change the answer of the login. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.isolated-context=new-device-notice-failure")
class NewDeviceNoticeFailureTest {

    private static final String PASSWORD = "senha-antiga-123";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    CustomerService customerService;

    private final HttpClient client = HttpClient.newHttpClient();

    private static String cookieValue(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(name + "="))
                .map(c -> c.substring(name.length() + 1, c.indexOf(';')))
                .reduce((first, last) -> last)
                .orElseThrow();
    }

    @Test
    @DisplayName("SEC-007: se o registro do aparelho falha, o login responde 204 e a sessão vale")
    void shouldStillLogInWhenTheDeviceRegistrationThrows() throws Exception {
        String email = "device-failure-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at)"
                        + " values (gen_random_uuid(), ?, ?, now())",
                email,
                passwordEncoder.encode(PASSWORD));
        doThrow(new IllegalStateException("scheduler down for " + email))
                .when(customerService)
                .recognizeDevice(any(), any(), any());
        String base = "http://localhost:" + port;
        HttpResponse<String> page = client.send(
                HttpRequest.newBuilder(URI.create(base + "/entrar")).build(), HttpResponse.BodyHandlers.ofString());
        String xsrf = cookieValue(page, "XSRF-TOKEN");

        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/sessions"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", "XSRF-TOKEN=" + xsrf)
                        .header("X-XSRF-TOKEN", xsrf)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(login.statusCode()).isEqualTo(204);
        HttpResponse<String> current = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/sessions/current"))
                        .header("Cookie", "JSESSIONID=" + cookieValue(login, "JSESSIONID"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(current.statusCode()).isEqualTo(200);
    }
}
