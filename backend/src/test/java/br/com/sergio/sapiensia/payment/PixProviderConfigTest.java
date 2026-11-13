package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;

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

    @Test
    @DisplayName("ADR-0003: shouldRefuseTheFakeGatewayInTheProdProfile")
    void shouldRefuseTheFakeGatewayInTheProdProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new FakePixGateway(prod)).isInstanceOf(IllegalStateException.class);
        assertThat(new FakePixGateway(new MockEnvironment())).isNotNull();
    }
}
