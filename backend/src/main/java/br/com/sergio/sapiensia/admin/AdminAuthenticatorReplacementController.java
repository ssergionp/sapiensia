package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.customer.ClientIpResolver;
import br.com.sergio.sapiensia.customer.PasswordLogin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The fully logged-in Administrator replaces the TOTP authenticator (panel(AUTHENTICATOR_REPLACE) in SecurityConfig).
 * Two steps: the start gives a new secret (and asks for the current TOTP code, except in a session that began with a
 * recovery code), the confirmation proves a code of the new secret and swaps it. The pending secret lives only in this
 * HTTP session, encrypted, so abandoning or losing the session discards it.
 */
@RestController
@RequestMapping("/api/admin/authenticator-replacement")
class AdminAuthenticatorReplacementController {

    /** The cipher text of the secret started in this session; it dies with the session. */
    private static final String REPLACEMENT_SECRET =
            AdminAuthenticatorReplacementController.class.getName() + ".replacementSecret";

    private final AdministratorMfa mfa;
    private final PasswordLogin passwordLogin;
    private final ClientIpResolver clientIp;
    private final Clock clock;
    private final SessionAuthenticationStrategy sessionStrategy = AdminSessionController.panelSessionStrategy();

    AdminAuthenticatorReplacementController(
            AdministratorMfa mfa, PasswordLogin passwordLogin, ClientIpResolver clientIp, Clock clock) {
        this.mfa = mfa;
        this.passwordLogin = passwordLogin;
        this.clientIp = clientIp;
        this.clock = clock;
    }

    /**
     * 200 with the new secret and its otpauth URI (never cached; the only answer that carries them), and the secret
     * kept in this session (a new start overwrites it); 400 CODE_INVALID for a wrong, malformed, used or missing
     * current code (a session from a recovery code needs none only within the recent-authentication window: the body is optional and any code is then ignored); 429
     * with Retry-After while locked; 409 when MFA is not active; 503 when the check or its audit cannot be made.
     */
    @PostMapping
    ResponseEntity<?> start(
            @RequestBody(required = false) MfaCodeRequest body,
            Authentication authentication,
            HttpSession session,
            HttpServletRequest request) {
        var administrator = (AdministratorPrincipal) authentication.getPrincipal();
        AdministratorMfa.Outcome outcome =
                mfa.startReplacement(administrator, body == null ? null : body.code(), clientIp.resolve(request));
        return switch (outcome.verdict()) {
            case ACCEPTED -> {
                session.setAttribute(REPLACEMENT_SECRET, outcome.started().secretCipher());
                yield ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(outcome.started().enrollment());
            }
            case REJECTED -> throw new InvalidMfaCodeException();
            case NOT_ENROLLED -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case LOCKED -> locked(outcome);
            case UNAVAILABLE ->
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        };
    }

    /**
     * 200 with the 10 new recovery codes (shown only here, never cached) and a new session (new id and CSRF token)
     * holding the same Administrator with the new session version, so every other session ends; 400 CODE_INVALID for
     * a wrong code of the new secret (the pending secret stays); 429 with Retry-After; 409 when no replacement was
     * started in this session, MFA is not active, or another confirmation won; 503 when the swap or its audit cannot
     * be made (then nothing changes).
     */
    @PostMapping(path = "/confirmation", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> confirm(
            @RequestBody MfaCodeRequest body,
            Authentication authentication,
            HttpSession session,
            HttpServletRequest request,
            HttpServletResponse response) {
        var administrator = (AdministratorPrincipal) authentication.getPrincipal();
        var pendingCipher = (String) session.getAttribute(REPLACEMENT_SECRET);
        AdministratorMfa.Outcome outcome =
                mfa.confirmReplacement(administrator, pendingCipher, body.code(), clientIp.resolve(request));
        return switch (outcome.verdict()) {
            case ACCEPTED -> {
                var renewed = new AdministratorPrincipal(
                        administrator.id(),
                        administrator.email(),
                        outcome.sessionVersion().orElseThrow(),
                        administrator.capabilities(),
                        administrator.passwordVerifiedAt(),
                        clock.instant(),
                        false);
                // the strategy ends this session, so the pending secret goes with it
                passwordLogin.startSession(
                        renewed,
                        UsernamePasswordAuthenticationToken.authenticated(renewed, null, renewed.getAuthorities()),
                        sessionStrategy,
                        request,
                        response);
                yield ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(new RecoveryCodeList(outcome.codes()));
            }
            case REJECTED -> throw new InvalidMfaCodeException();
            case NOT_ENROLLED -> ResponseEntity.status(HttpStatus.CONFLICT).build();
            case LOCKED -> locked(outcome);
            case UNAVAILABLE ->
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        };
    }

    private static ResponseEntity<Void> locked(AdministratorMfa.Outcome outcome) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(
                        HttpHeaders.RETRY_AFTER,
                        String.valueOf(outcome.retryAfter().toSeconds()))
                .build();
    }

    @ExceptionHandler(AdministratorMfa.UnavailableException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
