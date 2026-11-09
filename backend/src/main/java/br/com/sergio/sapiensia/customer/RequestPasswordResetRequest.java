package br.com.sergio.sapiensia.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record RequestPasswordResetRequest(
        @NotBlank(message = "EMAIL_INVALID")
        @Email(message = "EMAIL_INVALID")
        @Size(max = 254, message = "EMAIL_INVALID")
        String email) {

    // Surrounding spaces (autofill, paste) and letter case are not part of the e-mail.
    RequestPasswordResetRequest {
        if (email != null) {
            email = Emails.normalize(email);
        }
    }

    // never the e-mail
    @Override
    public String toString() {
        return "RequestPasswordResetRequest[redacted]";
    }
}
