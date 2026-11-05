package br.com.sergio.sapiensia.admin;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
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

    /** The loaded account: the principal for the session plus the hash the provider checks. */
    record Loaded(AdministratorPrincipal principal, String passwordHash) implements UserDetails {

        @Override
        public String toString() {
            return "Loaded[id=" + principal.id() + "]";
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return principal.getAuthorities();
        }

        @Override
        public String getPassword() {
            return passwordHash;
        }

        @Override
        public String getUsername() {
            return principal.email();
        }
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // An Administrator without a password is treated as unknown, so the provider hashes a dummy password in
        // all three failures (wrong password, unknown e-mail, no password) and they cost and answer the same.
        return administrators
                .findForLogin(email.trim().toLowerCase(Locale.ROOT))
                .filter(login -> login.passwordHash() != null)
                .map(login -> new Loaded(
                        new AdministratorPrincipal(
                                login.id(), login.email(), login.sessionVersion(), EnumSet.allOf(Capability.class)),
                        login.passwordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("unknown administrator"));
    }
}
