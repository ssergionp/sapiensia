package br.com.sergio.sapiensia.admin;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether the Administrator proved both factors (password and TOTP code) within app.admin.reauthentication-window, by
 * the application Clock. The full login counts, and so does a reauthentication; both stamp the same two instants of
 * the session principal. Public because the product package asks it before a published Product's file is replaced.
 * (A Spring Security factor "validDuration" is not used: the requirement depends on the Product's state in the
 * database, which an authorization manager of factors does not know.)
 */
@Component
public class RecentAuthentication {

    private final Clock clock;
    private final Duration window;

    RecentAuthentication(Clock clock, @Value("${app.admin.reauthentication-window}") Duration window) {
        if (window.isNegative() || window.isZero()) {
            throw new IllegalStateException("app.admin.reauthentication-window must be positive");
        }
        this.clock = clock;
        this.window = window;
    }

    /** True when both factors were verified after {@code now - window}. */
    public boolean isRecent(AdministratorPrincipal principal) {
        var oldest = clock.instant().minus(window);
        return principal.passwordVerifiedAt().isAfter(oldest)
                && principal.totpVerifiedAt().isAfter(oldest);
    }
}
