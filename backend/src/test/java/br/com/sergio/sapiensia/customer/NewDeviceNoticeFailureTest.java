package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

    private static final String DEVICE_TOKEN = "A".repeat(43);

    private final HttpClient client = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void captureLogs() {
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        rootLogger.detachAppender(logs);
        logs.stop();
    }

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
                .recognizeDevice(any(), anyInt(), any(), any());
        String base = "http://localhost:" + port;
        HttpResponse<String> page = client.send(
                HttpRequest.newBuilder(URI.create(base + "/entrar")).build(), HttpResponse.BodyHandlers.ofString());
        String xsrf = cookieValue(page, "XSRF-TOKEN");

        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/sessions"))
                        .header("Content-Type", "application/json")
                        .header("Cookie", "XSRF-TOKEN=" + xsrf + "; SAPIENSIA_DEVICE=" + DEVICE_TOKEN)
                        .header("X-XSRF-TOKEN", xsrf)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(login.statusCode()).isEqualTo(204);
        assertThat(login.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("SAPIENSIA_DEVICE="));
        List<ILoggingEvent> events = logs.list;
        assertThat(events)
                .anyMatch(e ->
                        e.getLevel() == Level.WARN && e.getFormattedMessage().contains("IllegalStateException"));
        assertThat(events)
                .noneMatch(e -> e.getFormattedMessage().contains(email)
                        || e.getFormattedMessage().contains(DEVICE_TOKEN)
                        || e.getThrowableProxy() != null
                                && String.valueOf(e.getThrowableProxy().getMessage())
                                        .contains(email));
        HttpResponse<String> current = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/sessions/current"))
                        .header("Cookie", "JSESSIONID=" + cookieValue(login, "JSESSIONID"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(current.statusCode()).isEqualTo(200);
    }
}
