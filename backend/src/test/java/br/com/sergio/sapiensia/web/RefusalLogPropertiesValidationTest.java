package br.com.sergio.sapiensia.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefusalLogPropertiesValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("SEC-014: shouldRejectANegativeInterval")
    void shouldRejectANegativeInterval() {
        assertThat(validator.validate(new RefusalLogProperties(Duration.ofSeconds(-1))))
                .hasSize(1);
    }

    @Test
    @DisplayName("SEC-014: shouldRejectAMissingInterval")
    void shouldRejectAMissingInterval() {
        assertThat(validator.validate(new RefusalLogProperties(null))).hasSize(1);
    }

    @Test
    @DisplayName("SEC-014: shouldAcceptZeroAndPositiveIntervals")
    void shouldAcceptZeroAndPositiveIntervals() {
        assertThat(validator.validate(new RefusalLogProperties(Duration.ZERO))).isEmpty();
        assertThat(validator.validate(new RefusalLogProperties(Duration.ofMinutes(1))))
                .isEmpty();
    }
}
