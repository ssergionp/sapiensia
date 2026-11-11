package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;

class AsaasWebhookPropertiesValidationTest {

    private static final String TOKEN = "a".repeat(32);

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptAValidConfiguration() {
        assertThat(validator.validate(new AsaasWebhookProperties(TOKEN, List.of("52.67.12.206"), 65536)))
                .isEmpty();
    }

    @Test
    @DisplayName("SEC-023: shouldRejectATokenShorterThan32OrWithWhitespace")
    void shouldRejectATokenShorterThan32OrWithWhitespace() {
        assertThat(validator.validate(new AsaasWebhookProperties("a".repeat(31), List.of("52.67.12.206"), 65536)))
                .hasSize(1);
        assertThat(validator.validate(new AsaasWebhookProperties(
                        "a".repeat(20) + " " + "b".repeat(20), List.of("52.67.12.206"), 65536)))
                .hasSize(1);
    }

    @Test
    void shouldRejectAnEmptyIpList() {
        assertThat(validator.validate(new AsaasWebhookProperties(TOKEN, List.of(), 65536)))
                .hasSize(1);
    }

    @Test
    @DisplayName("SEC-023: shouldRejectAnAllowedIpThatIsNotALiteralAddress")
    void shouldRejectAnAllowedIpThatIsNotALiteralAddress() {
        assertThatThrownBy(() -> new AsaasWebhookProperties(TOKEN, List.of("asaas.com"), 65536))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHideTheTokenInToString() {
        String text = new AsaasWebhookProperties(TOKEN, List.of("52.67.12.206"), 65536).toString();

        assertThat(text).doesNotContain(TOKEN).contains("token=[redacted]").contains("52.67.12.206");
    }

    @Test
    void shouldBeValidatedWhenBound() {
        assertThat(AsaasWebhookProperties.class.isAnnotationPresent(Validated.class))
                .isTrue();
    }
}
