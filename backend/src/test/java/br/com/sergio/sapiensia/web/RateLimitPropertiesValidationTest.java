package br.com.sergio.sapiensia.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;

class RateLimitPropertiesValidationTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final RateLimitProperties.Limit OK = new RateLimitProperties.Limit(5, WINDOW, 20, WINDOW);

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static RateLimitProperties with(int floor, RateLimitProperties.Limit orderCreate) {
        return new RateLimitProperties(floor, orderCreate, OK, OK, OK, OK, OK, OK, OK, OK);
    }

    @Test
    @DisplayName("SEC-014: shouldAcceptValidLimitsWithAndWithoutAccountPart")
    void shouldAcceptValidLimitsWithAndWithoutAccountPart() {
        assertThat(validator.validate(with(0, OK))).isEmpty();
        assertThat(validator.validate(with(10_000, new RateLimitProperties.Limit(null, null, 20, WINDOW))))
                .isEmpty();
    }

    @Test
    @DisplayName("SEC-014: shouldRejectNonPositiveCapacities")
    void shouldRejectNonPositiveCapacities() {
        assertThat(validator.validate(with(0, new RateLimitProperties.Limit(0, WINDOW, 20, WINDOW))))
                .hasSize(1);
        assertThat(validator.validate(with(0, new RateLimitProperties.Limit(5, WINDOW, 0, WINDOW))))
                .hasSize(1);
    }

    @Test
    @DisplayName("SEC-014: shouldRejectZeroNegativeOrMissingWindows")
    void shouldRejectZeroNegativeOrMissingWindows() {
        assertThat(validator.validate(with(0, new RateLimitProperties.Limit(5, WINDOW, 20, Duration.ZERO))))
                .isNotEmpty();
        assertThat(validator.validate(with(0, new RateLimitProperties.Limit(5, Duration.ofSeconds(-1), 20, WINDOW))))
                .isNotEmpty();
        assertThat(validator.validate(with(0, new RateLimitProperties.Limit(5, WINDOW, 20, null))))
                .isNotEmpty();
    }

    @Test
    @DisplayName("SEC-014: shouldRejectAnAccountCapacityWithoutWindowAndTheOtherWayAround")
    void shouldRejectAnAccountCapacityWithoutWindowAndTheOtherWayAround() {
        assertThat(validator.validate(with(0, new RateLimitProperties.Limit(5, null, 20, WINDOW))))
                .isNotEmpty();
        assertThat(validator.validate(with(0, new RateLimitProperties.Limit(null, WINDOW, 20, WINDOW))))
                .isNotEmpty();
    }

    @Test
    @DisplayName("SEC-014: shouldRejectNegativeFloorAndMissingOperation")
    void shouldRejectNegativeFloorAndMissingOperation() {
        assertThat(validator.validate(with(-1, OK))).hasSize(1);
        assertThat(validator.validate(with(0, null))).hasSize(1);
    }

    @Test
    void shouldBeValidatedWhenBound() {
        assertThat(RateLimitProperties.class.isAnnotationPresent(Validated.class))
                .isTrue();
    }
}
