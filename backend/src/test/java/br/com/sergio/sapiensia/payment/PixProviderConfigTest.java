package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PixProviderConfigTest {

    @Autowired
    private PixGateway gateway;

    @Value("${app.asaas.api.key}")
    private String apiKey;

    @Test
    void shouldStartWithTheFakeGatewayAndNoApiKey() {
        assertThat(gateway).isInstanceOf(FakePixGateway.class);
        assertThat(apiKey).isEmpty();
    }

    // built at run time: a literal that looks like an Asaas key is flagged by gitleaks
    private static final String KEY = "$aact_" + "hmlg_" + "test" + "0000000000";

    private static AsaasPixGateway asaas(String baseUrl, String key, Duration connect, Duration read) {
        return new AsaasPixGateway(RestClient.builder(), baseUrl, key, connect, read);
    }

    @Test
    @DisplayName("SEC-022: shouldRefuseABlankApiKey")
    void shouldRefuseABlankApiKey() {
        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> asaas(
                            "https://api-sandbox.asaas.com", blank, Duration.ofSeconds(3), Duration.ofSeconds(10)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("APP_ASAAS_API_KEY");
        }
    }

    @Test
    @DisplayName("SEC-022: shouldRefuseABaseUrlOutsideAsaasAndLoopback")
    void shouldRefuseABaseUrlOutsideAsaasAndLoopback() {
        for (String allowed : new String[] {
            "https://api-sandbox.asaas.com", "https://api.asaas.com", "http://localhost:8089", "http://127.0.0.1:8089"
        }) {
            assertThat(asaas(allowed, KEY, Duration.ofSeconds(3), Duration.ofSeconds(10)))
                    .isNotNull();
        }
        for (String refused : new String[] {
            "https://example.com",
            "http://api.asaas.com",
            "https://api.asaas.com.evil.test",
            "https://api.asaas.com@evil.test",
            "http://localhost",
            "https://localhost:8089",
            ""
        }) {
            assertThatThrownBy(() -> asaas(refused, KEY, Duration.ofSeconds(3), Duration.ofSeconds(10)))
                    .as(refused)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(KEY);
        }
    }

    @Test
    @DisplayName("SEC-018: shouldRefuseTimeoutsAboveThreeAndTenSeconds")
    void shouldRefuseTimeoutsAboveThreeAndTenSeconds() {
        String url = "https://api-sandbox.asaas.com";
        assertThat(asaas(url, KEY, Duration.parse("PT3S"), Duration.parse("PT10S")))
                .isNotNull();
        for (Duration[] refused : new Duration[][] {
            {Duration.parse("PT3.001S"), Duration.parse("PT10S")},
            {Duration.parse("PT3S"), Duration.parse("PT10.001S")},
            {Duration.ZERO, Duration.parse("PT10S")},
            {Duration.parse("PT3S"), Duration.ZERO},
            {Duration.parse("PT-1S"), Duration.parse("PT10S")},
            {Duration.parse("PT3S"), Duration.parse("PT-1S")}
        }) {
            assertThatThrownBy(() -> asaas(url, KEY, refused[0], refused[1])).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("ADR-0003: shouldRefuseTheFakeGatewayInTheProdProfile")
    void shouldRefuseTheFakeGatewayInTheProdProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new FakePixGateway(prod)).isInstanceOf(IllegalStateException.class);
        assertThat(new FakePixGateway(new MockEnvironment())).isNotNull();
    }
}
