package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.customer.Emails;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Loads the Administrator for the panel login. Deliberately not a Spring bean, so it never competes with the
 * customer's UserDetailsService; AdminSessionController builds its own provider around it.
 */
class AdministratorUserDetailsService implements UserDetailsService {

    private final Administrators administrators;

    AdministratorUserDetailsService(Administrators administrators) {
        this.administrators = administrators;
    }

    /**
     * The loaded account: what the pending session needs plus the hash the provider checks. No authorities: a correct
     * password alone grants nothing but the "MFA pending" state.
     */
    record Loaded(UUID id, String email, int sessionVersion, String passwordHash) implements UserDetails {

        // never the e-mail or the hash
        @Override
        public String toString() {
            return "Loaded[id=" + id + "]";
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of();
        }

        @Override
        public String getPassword() {
            return passwordHash;
        }

        @Override
        public String getUsername() {
            return email;
        }
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // An Administrator without a password is treated as unknown, so the provider hashes a dummy password in
        // all three failures (wrong password, unknown e-mail, no password) and they cost and answer the same.
        return administrators
                .findForLogin(Emails.normalize(email))
                .filter(login -> login.passwordHash() != null)
                .map(login -> new Loaded(login.id(), login.email(), login.sessionVersion(), login.passwordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("unknown administrator"));
    }
}
