package br.com.sergio.sapiensia.admin;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** The logged-in Administrator as kept in the session: no password hash, but the session version to check. */
public record AdministratorPrincipal(UUID id, String email, int sessionVersion, Set<Capability> capabilities)
        implements UserDetails {

    // never the e-mail: Spring Security logs the Authentication at DEBUG/TRACE
    @Override
    public String toString() {
        return "AdministratorPrincipal[id=" + id + "]";
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return capabilities.stream()
                .<GrantedAuthority>map(c -> new SimpleGrantedAuthority(c.authority()))
                .toList();
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
