package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;

class LoginLimitPropertiesValidationTest {

    private static final Duration MINUTES = Duration.ofMinutes(15);

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("SEC-002: shouldAcceptPositiveLimits")
    void shouldAcceptPositiveLimits() {
        assertThat(validator.validate(new LoginLimitProperties(5, MINUTES, MINUTES, 20, MINUTES, "")))
                .isEmpty();
    }

    @Test
    @DisplayName("SEC-014: shouldRejectNonPositiveCounts")
    void shouldRejectNonPositiveCounts() {
        assertThat(validator.validate(new LoginLimitProperties(0, MINUTES, MINUTES, 20, MINUTES, "")))
                .hasSize(1);
        assertThat(validator.validate(new LoginLimitProperties(5, MINUTES, MINUTES, -1, MINUTES, "")))
                .hasSize(1);
    }

    @Test
    @DisplayName("SEC-014: shouldRejectMissingDurations")
    void shouldRejectMissingDurations() {
        assertThat(validator.validate(new LoginLimitProperties(5, null, null, 20, null, "")))
                .hasSize(3);
    }

    @Test
    void shouldBeValidatedWhenBound() {
        assertThat(LoginLimitProperties.class.isAnnotationPresent(Validated.class))
                .isTrue();
    }
}
