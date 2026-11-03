package br.com.sergio.sapiensia.customer;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a customer for the login. Defining this bean also stops Spring Boot from generating a default user and
 * logging its password.
 */
@Service
class CustomerUserDetailsService implements UserDetailsService {

    private final CustomerRepository customers;

    CustomerUserDetailsService(CustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // The account is always "enabled" here: an unconfirmed e-mail must be refused only after the password check,
        // otherwise the answer (and its timing) would differ from a wrong password.
        return customers
                .findCredentialsByEmail(email.trim().toLowerCase(Locale.ROOT))
                .map(CustomerPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("unknown customer"));
    }

    /** The logged-in customer as kept in the session. */
    record CustomerPrincipal(CustomerCredentials credentials) implements UserDetails {

        boolean emailConfirmed() {
            return credentials.emailConfirmedAt() != null;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
        }

        @Override
        public String getPassword() {
            return credentials.passwordHash();
        }

        @Override
        public String getUsername() {
            return credentials.email();
        }
    }
}
