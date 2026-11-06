package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.customer.ClientIpResolver;
import br.com.sergio.sapiensia.customer.PasswordLogin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.EnumSet;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The second step of the panel login: the TOTP code of a pending Administrator. Only a {@link PendingAdministrator}
 * within app.admin.mfa-pending-ttl reaches it (AdminMfaAccess in SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin/sessions/mfa")
class AdminMfaController {

    private final AdministratorMfa mfa;
    private final PasswordLogin passwordLogin;
    private final ClientIpResolver clientIp;
    private final Clock clock;
    private final SessionAuthenticationStrategy sessionStrategy = AdminSessionController.panelSessionStrategy();

    AdminMfaController(AdministratorMfa mfa, PasswordLogin passwordLogin, ClientIpResolver clientIp, Clock clock) {
        this.mfa = mfa;
        this.passwordLogin = passwordLogin;
        this.clientIp = clientIp;
        this.clock = clock;
    }

    /**
     * 204 with a new session holding the full Administrator; 400 CODE_INVALID for a wrong, malformed or used code; 429
     * with Retry-After while locked; 409 when the account has no active MFA; 503 when the check cannot be made.
     */
    @PostMapping
    ResponseEntity<Void> verify(
            @RequestBody MfaCodeRequest body,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        var pending = (PendingAdministrator) authentication.getPrincipal();
        AdministratorMfa.Outcome outcome = mfa.verify(pending, body.code(), clientIp.resolve(request));
        return switch (outcome.verdict()) {
            case ACCEPTED -> {
                var principal = new AdministratorPrincipal(
                        pending.id(),
                        pending.email(),
                        pending.sessionVersion(),
                        EnumSet.allOf(Capability.class),
                        pending.passwordVerifiedAt(),
                        clock.instant());
                passwordLogin.startSession(
                        principal,
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()),
                        sessionStrategy,
                        request,
                        response);
                yield ResponseEntity.noContent().build();
            }
            case REJECTED -> throw new InvalidMfaCodeException();
            case NOT_ENROLLED -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case LOCKED ->
                ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(
                                HttpHeaders.RETRY_AFTER,
                                String.valueOf(outcome.retryAfter().toSeconds()))
                        .build();
            case UNAVAILABLE ->
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        };
    }
}
