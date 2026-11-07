package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.customer.ClientIpResolver;
import br.com.sergio.sapiensia.customer.PasswordLogin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.EnumSet;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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

    /** The cipher text of the enrollment started in this pending session (Ruling 3: no other secret confirms). */
    private static final String STARTED_ENROLLMENT = AdminMfaController.class.getName() + ".startedEnrollment";

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
        return answer(pending, mfa.verify(pending, body.code(), clientIp.resolve(request)), request, response);
    }

    /** Whether the Administrator already has an active authenticator (the SPA picks the enrollment or the code). */
    @GetMapping
    EnrolledStatus status(Authentication authentication) {
        return new EnrolledStatus(mfa.enrolled(((PendingAdministrator) authentication.getPrincipal()).id()));
    }

    /**
     * 200 with the new pending secret and its otpauth URI (never cached); 409 when MFA is already active. Only this
     * answer ever carries the secret.
     */
    @PostMapping("/enrollment")
    ResponseEntity<AdministratorMfa.Enrollment> enroll(Authentication authentication, HttpSession session) {
        return mfa.startEnrollment((PendingAdministrator) authentication.getPrincipal())
                .map(started -> {
                    // server-side only: the pending session is replaced at every login and at completion, so the
                    // enrollment dies with it
                    session.setAttribute(STARTED_ENROLLMENT, started.secretCipher());
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CACHE_CONTROL, "no-store")
                            .body(started.enrollment());
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    /**
     * 204 with a new session holding the full Administrator once the code confirms the secret enrolled in this same
     * session; 400 CODE_INVALID; 429 with Retry-After; 409 when MFA is already active, or no enrollment was started in
     * this session, or another session has replaced it since.
     */
    @PostMapping("/enrollment/confirmation")
    ResponseEntity<Void> confirm(
            @RequestBody MfaCodeRequest body,
            Authentication authentication,
            HttpSession session,
            HttpServletRequest request,
            HttpServletResponse response) {
        var pending = (PendingAdministrator) authentication.getPrincipal();
        var startedCipher = (String) session.getAttribute(STARTED_ENROLLMENT);
        return answer(
                pending,
                mfa.confirmEnrollment(pending, startedCipher, body.code(), clientIp.resolve(request)),
                request,
                response);
    }

    record EnrolledStatus(boolean enrolled) {}

    @ExceptionHandler(AdministratorMfa.UnavailableException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    private ResponseEntity<Void> answer(
            PendingAdministrator pending,
            AdministratorMfa.Outcome outcome,
            HttpServletRequest request,
            HttpServletResponse response) {
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
