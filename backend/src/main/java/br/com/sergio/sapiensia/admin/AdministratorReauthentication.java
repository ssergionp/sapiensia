package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import br.com.sergio.sapiensia.customer.Emails;
import br.com.sergio.sapiensia.customer.LoginAttemptLimiter;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * The password and the TOTP code of a full Administrator, typed again (SEC-008). It has no limiter of its own: the
 * password uses the key of the panel login ({@link AdminSessionController#accountKey}) and the code the key of the
 * code step ({@link AdministratorMfa#reauthenticate}), so wrong answers here and at the login add up in the same
 * buckets, and so does the per-IP budget. A wrong password does not even check the code.
 */
@Service
class AdministratorReauthentication {

    private static final Logger log = LoggerFactory.getLogger(AdministratorReauthentication.class);

    private static final int MAX_PASSWORD_LENGTH = 128;

    enum Verdict {
        ACCEPTED,
        REJECTED,
        LOCKED,
        UNAVAILABLE
    }

    /** {@code retryAfter} is set only for {@link Verdict#LOCKED}. */
    record Result(Verdict verdict, Duration retryAfter) {

        static Result of(Verdict verdict) {
            return new Result(verdict, null);
        }
    }

    private final Administrators administrators;
    private final PasswordEncoder passwordEncoder;
    private final AdministratorMfa mfa;
    private final LoginAttemptLimiter limiter;
    private final AuditLog audit;

    AdministratorReauthentication(
            Administrators administrators,
            PasswordEncoder passwordEncoder,
            AdministratorMfa mfa,
            LoginAttemptLimiter limiter,
            AuditLog audit) {
        this.administrators = administrators;
        this.passwordEncoder = passwordEncoder;
        this.mfa = mfa;
        this.limiter = limiter;
        this.audit = audit;
    }

    Result reauthenticate(AdministratorPrincipal who, String password, String code, String clientIp) {
        // such inputs can never match: refuse them without touching the limiter and without hashing
        if (password == null || code == null || password.length() > MAX_PASSWORD_LENGTH) {
            return Result.of(Verdict.REJECTED);
        }
        String email = Emails.normalize(who.email());
        String passwordKey = AdminSessionController.accountKey(email);
        LoginAttemptLimiter.Admission admission = limiter.admit(passwordKey, clientIp);
        if (!admission.allowed()) {
            audit.recordIndependent(entry(AuditResult.BLOCKED, who, clientIp));
            return new Result(Verdict.LOCKED, admission.blockedFor());
        }
        boolean passwordMatches;
        try {
            Optional<Administrators.Login> account = administrators.findForLogin(email);
            passwordMatches = account.isPresent()
                    && account.get().id().equals(who.id())
                    && passwordEncoder.matches(password, account.get().passwordHash());
        } catch (DataAccessException | IllegalStateException e) {
            // not a wrong password: the reserved place goes back. Only the class is logged.
            limiter.release(passwordKey, admission);
            log.error(
                    "Reauthentication unavailable: the account lookup failed ({})",
                    e.getClass().getName());
            return Result.of(Verdict.UNAVAILABLE);
        } catch (RuntimeException e) {
            limiter.release(passwordKey, admission);
            throw e;
        }
        if (!passwordMatches) {
            // the failure reserved by the admission stays in the bucket; the code is not even looked at
            audit.recordIndependent(entry(AuditResult.FAILURE, who, clientIp));
            return Result.of(Verdict.REJECTED);
        }
        // like the login: a right password clears only the password bucket
        limiter.recordSuccess(passwordKey);
        AdministratorMfa.Outcome outcome = mfa.reauthenticate(who.id(), code, clientIp);
        return switch (outcome.verdict()) {
            case ACCEPTED -> Result.of(Verdict.ACCEPTED);
            case REJECTED -> Result.of(Verdict.REJECTED);
            case LOCKED -> new Result(Verdict.LOCKED, outcome.retryAfter());
            case UNAVAILABLE -> Result.of(Verdict.UNAVAILABLE);
            case NOT_ENROLLED -> {
                // impossible for a full Administrator, so it is not a wrong answer: log it and refuse as unavailable
                log.error("Reauthentication unavailable: a full Administrator has no active MFA");
                yield Result.of(Verdict.UNAVAILABLE);
            }
        };
    }

    private static AuditEntry entry(AuditResult result, AdministratorPrincipal who, String clientIp) {
        return new AuditEntry(
                AuditAction.ADMIN_REAUTHENTICATION, result, who.id(), AuditObject.ADMINISTRATOR, who.id(), clientIp);
    }
}
