package br.com.sergio.sapiensia.customer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;

/** The logged-in Customer of a request, for other features that must not know the principal type. */
public final class CurrentCustomer {

    private CurrentCustomer() {}

    public static UUID id(Authentication authentication) {
        return principal(authentication).credentials().id();
    }

    /** The id when the authentication is a Customer's; empty for anonymous or any other principal (never throws). */
    public static Optional<UUID> idIfCustomer(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof CustomerUserDetailsService.CustomerPrincipal principal) {
            return Optional.of(principal.credentials().id());
        }
        return Optional.empty();
    }

    public static String email(Authentication authentication) {
        return principal(authentication).credentials().email();
    }

    private static CustomerUserDetailsService.CustomerPrincipal principal(Authentication authentication) {
        return (CustomerUserDetailsService.CustomerPrincipal) authentication.getPrincipal();
    }
}
