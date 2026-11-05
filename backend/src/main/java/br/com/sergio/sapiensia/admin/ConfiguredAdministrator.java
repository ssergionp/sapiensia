package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.customer.Emails;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Administrator e-mail from the APP_ADMIN_EMAIL secret, normalized and validated like the customer sign-up.
 * Blank or invalid: outside prod it is "not configured"; in prod ({@code app.admin.required}) the application does not
 * start. The error never shows the value.
 */
@Component
class ConfiguredAdministrator {

    private record Candidate(@Email @Size(max = 254) String email) {}

    private final Optional<String> email;

    ConfiguredAdministrator(
            @Value("${app.admin.email}") String rawEmail,
            @Value("${app.admin.required}") boolean required,
            Validator validator) {
        String normalized = Emails.normalize(rawEmail);
        boolean valid = !normalized.isEmpty()
                && validator.validate(new Candidate(normalized)).isEmpty();
        if (required && !valid) {
            throw new IllegalStateException(
                    "APP_ADMIN_EMAIL (app.admin.email) must be a valid e-mail address: the Administrator is created from it");
        }
        this.email = valid ? Optional.of(normalized) : Optional.empty();
    }

    /** The normalized e-mail, or empty when it is not configured or not valid. */
    Optional<String> email() {
        return email;
    }
}
