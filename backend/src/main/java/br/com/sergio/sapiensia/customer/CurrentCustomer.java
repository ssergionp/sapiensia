package br.com.sergio.sapiensia.customer;

import java.util.UUID;
import org.springframework.security.core.Authentication;

/** The logged-in Customer of a request, for other features that must not know the principal type. */
public final class CurrentCustomer {

    private CurrentCustomer() {}

    public static UUID id(Authentication authentication) {
        return principal(authentication).credentials().id();
    }

    public static String email(Authentication authentication) {
        return principal(authentication).credentials().email();
    }

    private static CustomerUserDetailsService.CustomerPrincipal principal(Authentication authentication) {
        return (CustomerUserDetailsService.CustomerPrincipal) authentication.getPrincipal();
    }
}
