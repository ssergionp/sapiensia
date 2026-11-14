package br.com.sergio.sapiensia.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the Pix gateway with {@link SwitchablePixGateway} in the tests that import it. It applies only with the
 * in-memory fake, so a context with {@code app.pix.provider=asaas} keeps the real adapter.
 */
@TestConfiguration
@ConditionalOnProperty(name = "app.pix.provider", havingValue = "fake")
public class SwitchablePixGatewayConfig {

    @Bean
    @Primary
    public SwitchablePixGateway switchablePixGateway(FakePixGateway fake) {
        return new SwitchablePixGateway(fake);
    }
}
