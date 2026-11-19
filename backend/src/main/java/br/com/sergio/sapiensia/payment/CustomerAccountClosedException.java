package br.com.sergio.sapiensia.payment;

import org.springframework.security.core.AuthenticationException;

/** The Customer's account was anonymized, so no purchase can start; the API answers 401 (FR-036). */
public final class CustomerAccountClosedException extends AuthenticationException {

    CustomerAccountClosedException() {
        super("Customer account is closed");
    }
}
