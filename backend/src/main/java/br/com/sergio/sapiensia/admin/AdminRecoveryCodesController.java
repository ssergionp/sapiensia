package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.customer.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A new set of recovery codes for the fully logged-in Administrator (panel(RECOVERY_CODES_MANAGE) in SecurityConfig),
 * who also types a fresh TOTP code: a stolen session alone cannot swap the codes.
 */
@RestController
@RequestMapping("/api/admin/recovery-codes")
class AdminRecoveryCodesController {

    private final AdministratorMfa mfa;
    private final ClientIpResolver clientIp;

    AdminRecoveryCodesController(AdministratorMfa mfa, ClientIpResolver clientIp) {
        this.mfa = mfa;
        this.clientIp = clientIp;
    }

    /**
     * 200 with the 10 new codes (shown only here, never cached; the old ones no longer work); 400 CODE_INVALID for a
     * wrong, malformed or already used TOTP code (the code of the login included); 429 with Retry-After while locked;
     * 409 when MFA is not active; 503 when the check or its audit cannot be made (then the old codes stay).
     */
    @PostMapping
    ResponseEntity<?> regenerate(
            @RequestBody MfaCodeRequest body, Authentication authentication, HttpServletRequest request) {
        var administrator = (AdministratorPrincipal) authentication.getPrincipal();
        AdministratorMfa.Outcome outcome =
                mfa.regenerateCodes(administrator.id(), body.code(), clientIp.resolve(request));
        return switch (outcome.verdict()) {
            case ACCEPTED ->
                ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(new RecoveryCodeList(outcome.codes()));
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
