package br.com.sergio.sapiensia.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Same rules and error codes as the Customer's password reset request, so both pages show the same messages. */
record AdminPasswordSetupRequest(
        @NotBlank(message = "TOKEN_INVALID") @Size(max = 64, message = "TOKEN_INVALID")
        String token,

        // never trimmed: spaces at the ends are part of the password
        @NotBlank(message = "PASSWORD_REQUIRED")
        @Size(min = 12, message = "PASSWORD_TOO_SHORT")
        @Size(max = 128, message = "PASSWORD_TOO_LONG")
        String newPassword) {}
