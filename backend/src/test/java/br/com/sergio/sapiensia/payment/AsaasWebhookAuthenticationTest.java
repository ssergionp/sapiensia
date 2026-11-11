package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.TOKEN;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.event;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=asaas-webhook-auth", "app.asaas.webhook.allowed-ips=127.0.0.21"})
class AsaasWebhookAuthenticationTest {

    private static final String HEADER = AsaasWebhookAccess.TOKEN_HEADER;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private HttpResponse<String> post(String from, Map<String, String> headers) throws Exception {
        return AsaasWebhookTestSupport.post(port, from, headers);
    }

    /** A refused request must not reach the controller: no row exists for the event it carried. */
    private HttpResponse<String> postRefused(String from, Map<String, String> headers) throws Exception {
        String id = uniqueId();
        HttpResponse<String> response =
                AsaasWebhookTestSupport.post(port, from, headers, event(id, "PAYMENT_RECEIVED"), "application/json");
        assertThat(jdbc.queryForObject(
                        "select count(*) from payment_events where provider_event_id = ?", Integer.class, id))
                .isZero();
        return response;
    }

    @Test
    @DisplayName("SEC-023: shouldRefuseWithoutTheToken")
    void shouldRefuseWithoutTheToken() throws Exception {
        HttpResponse<String> response = postRefused("127.0.0.21", Map.of());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("SEC-023: shouldRefuseAWrongToken")
    void shouldRefuseAWrongToken() throws Exception {
        String oneCharChanged = TOKEN.substring(0, TOKEN.length() - 1) + "1";

        assertThat(postRefused("127.0.0.21", Map.of(HEADER, oneCharChanged)).statusCode())
                .isEqualTo(401);
        assertThat(postRefused("127.0.0.21", Map.of(HEADER, TOKEN + TOKEN)).statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-023: shouldRefuseAnIpOutsideTheList")
    void shouldRefuseAnIpOutsideTheList() throws Exception {
        assertThat(postRefused("127.0.0.22", Map.of(HEADER, TOKEN)).statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-023: shouldIgnoreXForwardedForWhenNoHeaderIsConfigured")
    void shouldIgnoreXForwardedForWhenNoHeaderIsConfigured() throws Exception {
        assertThat(postRefused("127.0.0.22", Map.of(HEADER, TOKEN, "X-Forwarded-For", "127.0.0.21"))
                        .statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-023: shouldAcceptTokenAndIp")
    void shouldAcceptTokenAndIp() throws Exception {
        assertThat(post("127.0.0.21", Map.of(HEADER, TOKEN)).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-023: shouldNotNeedCsrfNorOpenASession")
    void shouldNotNeedCsrfNorOpenASession() throws Exception {
        // no XSRF-TOKEN cookie and no X-XSRF-TOKEN header are sent
        HttpResponse<String> response = post("127.0.0.21", Map.of(HEADER, TOKEN));

        assertThat(response.statusCode()).isEqualTo(200);
        // csrf.spa() may still write an XSRF-TOKEN cookie; the webhook must only never open a session
        assertThat(response.headers().allValues("set-cookie")).noneMatch(c -> c.startsWith("JSESSIONID"));
    }

    @Test
    @DisplayName("SEC-010: shouldKeepOtherVerbsDenied")
    void shouldKeepOtherVerbsDenied() throws Exception {
        assertThat(AsaasWebhookTestSupport.send(port, "GET", "127.0.0.21", Map.of(HEADER, TOKEN))
                        .statusCode())
                .isEqualTo(401);
    }
}
