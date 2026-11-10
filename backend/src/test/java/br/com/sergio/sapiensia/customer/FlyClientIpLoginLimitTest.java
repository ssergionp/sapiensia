package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
// Real HTTP server, so the forwarded-headers filter that the prod profile enables is in play.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=fly-client-ip-login-limit",
            "server.forward-headers-strategy=framework",
            "app.login-limit.ip-max-attempts=3",
            "app.login-limit.client-ip-header=Fly-Client-IP"
        })
class FlyClientIpLoginLimitTest {

    private static final int IP_LIMIT = 3;

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();
    String csrf;

    @BeforeEach
    void setUp() throws Exception {
        HttpResponse<String> page = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/entrar"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        csrf = page.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN="))
                .map(c -> c.substring("XSRF-TOKEN=".length(), c.indexOf(';')))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Each test connects from its own loopback source address (the server sees it as the connection address), so the
     * connection-address limit of one test never touches another's, whatever the order they run in.
     */
    private int login(String loopback, String forwardedFor, String flyClientIp) throws Exception {
        // the client binds to that source address, which is what the server sees as the connection address
        HttpClient http = HttpClient.newBuilder()
                .localAddress(java.net.InetAddress.getByName(loopback))
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/sessions"))
                .header("Cookie", "XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + UUID.randomUUID()
                        + "@example.com\",\"password\":\"senhaerrada123\"}"));
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        if (flyClientIp != null) {
            builder.header("Fly-Client-IP", flyClientIp);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    @DisplayName("SEC-014: without forwarded headers the login limit counts on the connection address")
    void shouldCountOnConnectionAddressWithoutHeaders() throws Exception {
        for (int i = 1; i <= IP_LIMIT; i++) {
            assertThat(login("127.0.0.11", null, null)).as("attempt %d", i).isEqualTo(401);
        }

        assertThat(login("127.0.0.11", null, null)).isEqualTo(429);
    }

    @Test
    @DisplayName("SEC-014: a different forged X-Forwarded-For on each attempt does not escape the limit")
    void shouldNotLetForgedXForwardedForEscapeTheLimit() throws Exception {
        for (int i = 1; i <= IP_LIMIT; i++) {
            assertThat(login("127.0.0.12", "198.51.100." + i, null)).as("attempt %d", i).isEqualTo(401);
        }

        assertThat(login("127.0.0.12", "198.51.100.200", null)).isEqualTo(429);
    }

    @Test
    @DisplayName("SEC-014: a valid Fly-Client-IP is the address counted, whatever X-Forwarded-For says")
    void shouldCountOnFlyClientIpWhenPresent() throws Exception {
        for (int i = 1; i <= IP_LIMIT; i++) {
            assertThat(login("127.0.0.13", "198.51.100." + i, "203.0.113.50")).as("attempt %d", i).isEqualTo(401);
        }

        assertThat(login("127.0.0.13", "198.51.100.99", "203.0.113.50")).isEqualTo(429);
        assertThat(login("127.0.0.13", "198.51.100.99", "203.0.113.51")).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-014: an invalid Fly-Client-IP falls back to the connection address, never X-Forwarded-For")
    void shouldFallBackToConnectionAddressWhenFlyClientIpIsInvalid() throws Exception {
        for (int i = 1; i <= IP_LIMIT; i++) {
            assertThat(login("127.0.0.14", "198.51.100." + i, "not-an-ip")).as("attempt %d", i).isEqualTo(401);
        }

        assertThat(login("127.0.0.14", "198.51.100.200", "not-an-ip")).isEqualTo(429);
    }
}
