package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.event;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Security proof: route, verb and header variants of the webhook, sent with a valid token from the allowed address,
 * never reach the controller (no 200 and no stored event).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.isolated-context=asaas-webhook-persistence")
class AsaasWebhookRouteVariantsTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String HEADER = AsaasWebhookAccess.TOKEN_HEADER;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Environment environment;

    private String token() {
        return environment.getRequiredProperty("app.asaas.webhook.token");
    }

    private HttpResponse<String> send(String method, String path, String id, String... headerPairs) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .localAddress(InetAddress.getByName(LOOPBACK))
                .build();
        HttpRequest.BodyPublisher body = "POST".equals(method)
                ? HttpRequest.BodyPublishers.ofString(event(id, "PAYMENT_RECEIVED"))
                : HttpRequest.BodyPublishers.noBody();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body)
                .header("Content-Type", "application/json");
        for (int i = 0; i < headerPairs.length; i += 2) {
            builder.header(headerPairs[i], headerPairs[i + 1]);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response;
    }

    private int count(String id) {
        return jdbc.queryForObject(
                "select count(*) from payment_events where provider_event_id = ?", Integer.class, id);
    }

    private void assertRefused(String method, String path) throws Exception {
        String id = uniqueId();
        HttpResponse<String> response = send(method, path, id, HEADER, token());
        assertThat(response.statusCode()).as("%s %s", method, path).isNotEqualTo(200);
        assertThat(count(id)).as("rows for %s %s", method, path).isZero();
    }

    @Test
    @DisplayName("SEC-010, SEC-023: shouldDenyTheRouteWithATrailingSlash")
    void shouldDenyTheRouteWithATrailingSlash() throws Exception {
        // Expected layer: the exact-path matcher does not match, so /api/** denyAll applies (or the app has no route).
        // Observed: 403.
        assertRefused("POST", "/api/webhooks/asaas/");
    }

    @Test
    @DisplayName("SEC-010, SEC-023: shouldDenyTheRouteWithADoubleSlash")
    void shouldDenyTheRouteWithADoubleSlash() throws Exception {
        // Expected layer: StrictHttpFirewall rejects the double slash (400), or the security chain answers 401.
        // Observed: 400.
        assertRefused("POST", "/api//webhooks/asaas");
    }

    @Test
    @DisplayName("SEC-010, SEC-023: shouldDenyTheRouteWithAPathParameter")
    void shouldDenyTheRouteWithAPathParameter() throws Exception {
        // Expected layer: StrictHttpFirewall rejects the semicolon (400).
        // Observed: 400.
        assertRefused("POST", "/api/webhooks/asaas;jsessionid=abc");
    }

    @Test
    @DisplayName("SEC-010, SEC-023: shouldDenyTheRouteWithAnotherCase")
    void shouldDenyTheRouteWithAnotherCase() throws Exception {
        // Expected layer: the case-sensitive matchers do not match, so /api/** denyAll applies (or no route exists).
        // Observed: 403 for both.
        assertRefused("POST", "/API/webhooks/asaas");
        assertRefused("POST", "/api/Webhooks/asaas");
    }

    @Test
    @DisplayName("SEC-010, SEC-023: shouldDenyTheRouteWithASuffix")
    void shouldDenyTheRouteWithASuffix() throws Exception {
        // Expected layer: no suffix pattern matching; /api/** denyAll applies.
        // Observed: 403.
        assertRefused("POST", "/api/webhooks/asaas.json");
    }

    @Test
    @DisplayName("SEC-010, SEC-023: shouldDenyOtherVerbs")
    void shouldDenyOtherVerbs() throws Exception {
        // Expected layer: only POST is matched by the access rule, so /api/** denyAll applies to every other verb.
        // Observed: GET, HEAD, OPTIONS 401; PUT, DELETE, PATCH 403 (CSRF refuses the verb before authorization).
        for (String method : new String[] {"GET", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}) {
            assertRefused(method, "/api/webhooks/asaas");
        }
    }

    @Test
    @DisplayName("SEC-010, SEC-023: shouldUseTheFirstValueOfADuplicatedTokenHeader")
    void shouldUseTheFirstValueOfADuplicatedTokenHeader() throws Exception {
        // Expected layer: AsaasWebhookAccess reads the first header value; a wrong first value is refused (401).
        String wrongFirst = uniqueId();
        HttpResponse<String> refused =
                send("POST", "/api/webhooks/asaas", wrongFirst, HEADER, "wrong", HEADER, token());
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(count(wrongFirst)).isZero();

        // Documents that the attacker cannot choose which value is checked without already knowing the token.
        String rightFirst = uniqueId();
        HttpResponse<String> accepted =
                send("POST", "/api/webhooks/asaas", rightFirst, HEADER, token(), HEADER, "wrong");
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(count(rightFirst)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-010, SEC-023: shouldDenyAnEmptyTokenHeader")
    void shouldDenyAnEmptyTokenHeader() throws Exception {
        // Expected layer: AsaasWebhookAccess token comparison fails (401).
        String id = uniqueId();
        HttpResponse<String> response = send("POST", "/api/webhooks/asaas", id, HEADER, "");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(count(id)).isZero();
    }
}
