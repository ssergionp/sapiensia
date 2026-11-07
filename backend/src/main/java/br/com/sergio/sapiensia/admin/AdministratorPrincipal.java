package br.com.sergio.sapiensia.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The fully logged-in Administrator (password and TOTP code) as kept in the session: no password hash, but the session
 * version to check. Created only after the code is accepted. Authorities: the capabilities plus the two factors.
 */
public record AdministratorPrincipal(
        UUID id,
        String email,
        int sessionVersion,
        Set<Capability> capabilities,
        Instant passwordVerifiedAt,
        Instant totpVerifiedAt)
        implements UserDetails {

    /** The authority of the TOTP factor; Spring Security has no constant for it. */
    public static final String TOTP_AUTHORITY = "FACTOR_TOTP";

    // never the e-mail: Spring Security logs the Authentication at DEBUG/TRACE
    @Override
    public String toString() {
        return "AdministratorPrincipal[id=" + id + "]";
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        capabilities.forEach(c -> authorities.add(new SimpleGrantedAuthority(c.authority())));
        authorities.add(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(passwordVerifiedAt)
                .build());
        authorities.add(FactorGrantedAuthority.withAuthority(TOTP_AUTHORITY)
                .issuedAt(totpVerifiedAt)
                .build());
        return authorities;
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
