package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LoginLimitPropertiesTest {

    @Autowired
    LoginLimitProperties properties;

    @Autowired
    Clock clock;

    @Test
    void bindsTheAgreedDefaults() {
        assertThat(properties.accountMaxFailures()).isEqualTo(5);
        assertThat(properties.accountWindow()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.accountLock()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.ipMaxAttempts()).isEqualTo(20);
        assertThat(properties.ipWindow()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.clientIpHeader()).isBlank();
        assertThat(clock).isNotNull();
    }
}
