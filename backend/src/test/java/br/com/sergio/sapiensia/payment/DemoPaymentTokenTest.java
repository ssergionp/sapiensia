package br.com.sergio.sapiensia.payment;

import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.event;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.post;
import static br.com.sergio.sapiensia.payment.AsaasWebhookTestSupport.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The fictitious token of `just demo-pagar` exists only in the local profile (and in scripts/demo-pagar.mjs). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=demo-payment-token", "app.asaas.webhook.allowed-ips=127.0.0.23"})
class DemoPaymentTokenTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    /** The value after the default separator of the token line in application-local.yml. */
    private static String localToken() throws Exception {
        Matcher m = Pattern.compile("token: \\$\\{APP_ASAAS_WEBHOOK_TOKEN:([^}]+)}")
                .matcher(Files.readString(RESOURCES.resolve("application-local.yml")));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("SEC-022: shouldNotHaveTheLocalWebhookTokenOutsideTheLocalProfile")
    void shouldNotHaveTheLocalWebhookTokenOutsideTheLocalProfile() throws Exception {
        String token = localToken();
        String base = Files.readString(RESOURCES.resolve("application.yml"));

        assertThat(base).doesNotContain(token);
        assertThat(Files.readString(RESOURCES.resolve("application-prod.yml"))).doesNotContain(token);
        assertThat(base).contains("token: ${APP_ASAAS_WEBHOOK_TOKEN}\n");
    }

    @Test
    @DisplayName("SEC-022: shouldRefuseTheLocalDemoTokenWhenAnotherTokenIsConfigured")
    void shouldRefuseTheLocalDemoTokenWhenAnotherTokenIsConfigured() throws Exception {
        String id = uniqueId();

        HttpResponse<String> response = post(
                port,
                "127.0.0.23",
                Map.of(AsaasWebhookAccess.TOKEN_HEADER, localToken()),
                event(id, "PAYMENT_RECEIVED"),
                "application/json");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject(
                        "select count(*) from payment_events where provider_event_id = ?", Integer.class, id))
                .isZero();
    }
}
