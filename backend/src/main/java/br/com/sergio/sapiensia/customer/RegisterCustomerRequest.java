package br.com.sergio.sapiensia.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record RegisterCustomerRequest(
        @NotBlank(message = "EMAIL_INVALID")
        @Email(message = "EMAIL_INVALID")
        @Size(max = 254, message = "EMAIL_INVALID")
        String email,

        @NotBlank(message = "PASSWORD_REQUIRED")
        @Size(min = 12, message = "PASSWORD_TOO_SHORT")
        @Size(max = 128, message = "PASSWORD_TOO_LONG")
        String password) {}
