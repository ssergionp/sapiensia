package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** Guard: no log line may carry the webhook token, any part of an event body or personal data. */
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=asaas-webhook-log",
            "app.asaas.webhook.allowed-ips=127.0.0.1",
            // the noisiest levels that could print a request, a body or a SQL parameter
            "logging.level.org.springframework.security=TRACE",
            "logging.level.org.springframework.web=DEBUG",
            "logging.level.br.com.sergio.sapiensia=TRACE"
        })
class AsaasWebhookLogTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String HEADER = "asaas-access-token";

    @LocalServerPort
    int port;

    @Autowired
    Environment environment;

    @Test
    @DisplayName("SEC-023, ADR-0006: shouldNeverLogTheTokenTheBodyOrPersonalData")
    void shouldNeverLogTheTokenTheBodyOrPersonalData(CapturedOutput output) throws Exception {
        String token = environment.getProperty("app.asaas.webhook.token");
        assertThat(token).isNotBlank();
        Map<String, String> good = Map.of(HEADER, token);

        // (a) a valid event carrying marker values, (b) the same event again
        String id = AsaasWebhookTestSupport.uniqueId();
        String marker = "MARKER-" + UUID.randomUUID();
        String event = """
                {"id": "%s", "event": "PAYMENT_RECEIVED", "dateCreated": "2026-11-11 10:00:00",
                 "payment": {"object": "payment", "id": "pay_000", "customer": "cus_MARKER2", "value": 59.9,
                             "description": "%s", "billingType": "PIX", "status": "RECEIVED"}}""".formatted(id, marker);
        assertThat(AsaasWebhookTestSupport.post(port, LOOPBACK, good, event, "application/json")
                        .statusCode())
                .isEqualTo(200);
        assertThat(AsaasWebhookTestSupport.post(port, LOOPBACK, good, event, "application/json")
                        .statusCode())
                .isEqualTo(200);

        // (c) a wrong token
        String wrong = "WRONG-" + UUID.randomUUID();
        assertThat(AsaasWebhookTestSupport.post(port, LOOPBACK, Map.of(HEADER, wrong))
                        .statusCode())
                .isIn(401, 403);

        // (d) a malformed body that contains a marker
        AsaasWebhookTestSupport.post(port, LOOPBACK, good, "{\"id\": \"MARKER3\", ", "application/json");

        // (e) a body with a personal document number
        String personal = AsaasWebhookTestSupport.event(AsaasWebhookTestSupport.uniqueId(), "PAYMENT_RECEIVED")
                .replace("\"customer\": \"cus_000\"", "\"customer\": \"cus_000\", \"cpfCnpj\": \"98765432100\"");
        assertThat(personal).contains("98765432100");
        AsaasWebhookTestSupport.post(port, LOOPBACK, good, personal, "application/json");

        String logs = output.getAll();
        assertThat(logs).as("the flow logged something").isNotBlank();
        assertThat(logs)
                .doesNotContain(token)
                .doesNotContain("MARKER-")
                .doesNotContain("cus_MARKER2")
                .doesNotContain("WRONG-")
                .doesNotContain("MARKER3")
                .doesNotContain("98765432100");
        assertThat(logs)
                .contains("Asaas webhook event stored: id=" + id)
                .contains("Asaas webhook event duplicate: id=" + id)
                .contains("Asaas webhook refused: ip=127.0.0.1 reason=TOKEN_INVALID");
    }
}
