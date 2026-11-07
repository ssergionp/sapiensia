package br.com.sergio.sapiensia.admin;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The Administrator between the correct password and the TOTP code ("MFA pending"), as kept in the session. Its only
 * authority is the password factor: no capability, so no panel route accepts it; only the MFA routes do, for
 * app.admin.mfa-pending-ttl after {@code passwordVerifiedAt} (see AdminMfaAccess). Public for SecurityConfig and
 * SessionVersionFilter.
 */
public record PendingAdministrator(UUID id, String email, int sessionVersion, Instant passwordVerifiedAt)
        implements UserDetails {

    // never the e-mail: Spring Security logs the Authentication at DEBUG/TRACE
    @Override
    public String toString() {
        return "PendingAdministrator[id=" + id + "]";
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(passwordVerifiedAt)
                .build());
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
