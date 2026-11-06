package br.com.sergio.sapiensia.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record ResetPasswordRequest(
        @NotBlank(message = "TOKEN_INVALID") @Size(max = 64, message = "TOKEN_INVALID")
        String token,

        // never trimmed: spaces at the ends are part of the password
        @NotBlank(message = "PASSWORD_REQUIRED")
        @Size(min = 12, message = "PASSWORD_TOO_SHORT")
        @Size(max = 128, message = "PASSWORD_TOO_LONG")
        String newPassword) {}
