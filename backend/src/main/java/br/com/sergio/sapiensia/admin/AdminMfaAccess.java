package br.com.sergio.sapiensia.admin;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Who may use the MFA routes: only a {@link PendingAdministrator} whose password was verified less than
 * app.admin.mfa-pending-ttl ago (by the application Clock). Anyone else is denied: anonymous 401, a Customer or a full
 * Administrator 403, an expired pending Administrator 401 (SecurityConfig's AccessDeniedHandler).
 */
@Component
public class AdminMfaAccess implements AuthorizationManager<RequestAuthorizationContext> {

    private final Clock clock;
    private final Duration pendingTtl;

    AdminMfaAccess(Clock clock, @Value("${app.admin.mfa-pending-ttl}") Duration pendingTtl) {
        if (pendingTtl.isNegative() || pendingTtl.isZero()) {
            throw new IllegalStateException("app.admin.mfa-pending-ttl must be positive");
        }
        this.clock = clock;
        this.pendingTtl = pendingTtl;
    }

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        Authentication current = authentication.get();
        boolean granted = current != null
                && current.getPrincipal() instanceof PendingAdministrator pending
                && clock.instant().isBefore(pending.passwordVerifiedAt().plus(pendingTtl));
        return new AuthorizationDecision(granted);
    }
}
