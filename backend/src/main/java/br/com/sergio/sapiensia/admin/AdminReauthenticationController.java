package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.customer.ClientIpResolver;
import br.com.sergio.sapiensia.customer.PasswordLogin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
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
 * The full Administrator proves password and TOTP code again (panel(PANEL_ACCESS) in SecurityConfig), which stamps both
 * factors with the current time (see {@link RecentAuthentication}) in a new session with a new CSRF token.
 */
@RestController
@RequestMapping("/api/admin/sessions/reauthentication")
class AdminReauthenticationController {

    private final AdministratorReauthentication reauthentication;
    private final PasswordLogin passwordLogin;
    private final ClientIpResolver clientIp;
    private final Clock clock;
    private final SessionAuthenticationStrategy sessionStrategy = AdminSessionController.panelSessionStrategy();

    AdminReauthenticationController(
            AdministratorReauthentication reauthentication,
            PasswordLogin passwordLogin,
            ClientIpResolver clientIp,
            Clock clock) {
        this.reauthentication = reauthentication;
        this.passwordLogin = passwordLogin;
        this.clientIp = clientIp;
        this.clock = clock;
    }

    /**
     * 204 with a new session id and CSRF token, holding the same Administrator with both factors renewed; 400
     * CREDENTIALS_INVALID for a wrong password or a wrong, malformed or used code (one answer for all); 429 with
     * Retry-After while the account or the IP is locked; 503 when the check or its audit cannot be made.
     */
    @PostMapping
    ResponseEntity<Void> reauthenticate(
            @RequestBody ReauthenticationRequest body,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        var current = (AdministratorPrincipal) authentication.getPrincipal();
        AdministratorReauthentication.Result result =
                reauthentication.reauthenticate(current, body.password(), body.code(), clientIp.resolve(request));
        return switch (result.verdict()) {
            case ACCEPTED -> {
                var now = clock.instant();
                var renewed = new AdministratorPrincipal(
                        current.id(), current.email(), current.sessionVersion(), current.capabilities(), now, now);
                passwordLogin.startSession(
                        renewed,
                        UsernamePasswordAuthenticationToken.authenticated(renewed, null, renewed.getAuthorities()),
                        sessionStrategy,
                        request,
                        response);
                yield ResponseEntity.noContent().build();
            }
            case REJECTED -> throw new InvalidReauthenticationException();
            case LOCKED ->
                ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header(
                                HttpHeaders.RETRY_AFTER,
                                String.valueOf(result.retryAfter().toSeconds()))
                        .build();
            case UNAVAILABLE ->
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        };
    }
}
