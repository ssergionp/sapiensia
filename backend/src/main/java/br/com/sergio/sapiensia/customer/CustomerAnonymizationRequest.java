package br.com.sergio.sapiensia.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The e-mail of the account to anonymize, in the body (never in the URL: it would reach access logs). */
record CustomerAnonymizationRequest(
        @NotBlank(message = "EMAIL_INVALID")
        @Email(message = "EMAIL_INVALID")
        @Size(max = 254, message = "EMAIL_INVALID")
        String email) {

    // Surrounding spaces and letter case are not part of the e-mail, as in the sign-up.
    CustomerAnonymizationRequest {
        if (email != null) {
            email = Emails.normalize(email);
        }
    }

    // never the e-mail
    @Override
    public String toString() {
        return "CustomerAnonymizationRequest[redacted]";
    }
}
