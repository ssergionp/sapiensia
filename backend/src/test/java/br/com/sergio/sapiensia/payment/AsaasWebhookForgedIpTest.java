package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** Behind the platform proxy (forwarded headers on), only Fly-Client-IP may stand for the source address. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=asaas-webhook-forged-ip",
            "server.forward-headers-strategy=framework",
            "app.login-limit.client-ip-header=Fly-Client-IP",
            "app.asaas.webhook.allowed-ips=127.0.0.31"
        })
class AsaasWebhookForgedIpTest {

    private static final String HEADER = AsaasWebhookAccess.TOKEN_HEADER;

    @LocalServerPort
    int port;

    private int status(String from, Map<String, String> headers) throws Exception {
        return AsaasWebhookTestSupport.post(port, from, headers).statusCode();
    }

    @Test
    @DisplayName("SEC-023: shouldTrustFlyClientIpBehindTheProxy")
    void shouldTrustFlyClientIpBehindTheProxy() throws Exception {
        assertThat(status("127.0.0.32", Map.of(HEADER, TOKEN, "Fly-Client-IP", "127.0.0.31")))
                .isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-023: shouldNeverTrustXForwardedFor")
    void shouldNeverTrustXForwardedFor() throws Exception {
        assertThat(status("127.0.0.32", Map.of(HEADER, TOKEN, "X-Forwarded-For", "127.0.0.31")))
                .isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-023: shouldFallBackToTheConnectionWhenFlyClientIpIsInvalid")
    void shouldFallBackToTheConnectionWhenFlyClientIpIsInvalid() throws Exception {
        assertThat(status("127.0.0.32", Map.of(HEADER, TOKEN, "Fly-Client-IP", "not-an-ip")))
                .isEqualTo(401);
        assertThat(status("127.0.0.31", Map.of(HEADER, TOKEN, "Fly-Client-IP", "not-an-ip")))
                .isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-023: shouldRefuseAForgedFlyClientIpFromAnUnknownConnection")
    void shouldRefuseAForgedFlyClientIpFromAnUnknownConnection() throws Exception {
        assertThat(status("127.0.0.32", Map.of(HEADER, "wrong-token", "Fly-Client-IP", "127.0.0.31")))
                .isEqualTo(401);
    }
}
