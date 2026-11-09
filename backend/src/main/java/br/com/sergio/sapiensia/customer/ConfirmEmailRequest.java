package br.com.sergio.sapiensia.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record ConfirmEmailRequest(
        @NotBlank(message = "TOKEN_INVALID") @Size(max = 64, message = "TOKEN_INVALID")
        String token,

        @NotBlank(message = "PASSWORD_REQUIRED") @Size(max = 128, message = "PASSWORD_INCORRECT")
        String password) {

    // never the link token or the password
    @Override
    public String toString() {
        return "ConfirmEmailRequest[redacted]";
    }
}
