package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.customer.LoginAttemptLimiter;
import br.com.sergio.sapiensia.security.SecretCipher;
import br.com.sergio.sapiensia.security.Totp;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Checks the TOTP code of a pending Administrator: the login limiter (its own account key, the IP bucket shared with
 * the logins), the decrypted secret, the code of the steps t-1..t+1, and the replay guard (a step is accepted only
 * once, and only if greater than the last accepted one).
 */
@Service
class AdministratorMfa {

    private static final Logger log = LoggerFactory.getLogger(AdministratorMfa.class);

    // the account's code failures; from the session principal, never from a typed field, so nothing to normalize. The
    // prefix keeps it apart from "admin:<e-mail>" and "customer:<e-mail>".
    private static final String ACCOUNT_KEY_PREFIX = "admin-mfa:";

    enum Verdict {
        ACCEPTED,
        REJECTED,
        NOT_ENROLLED,
        LOCKED,
        UNAVAILABLE
    }

    /** {@code retryAfter} is set only for {@link Verdict#LOCKED}. */
    record Outcome(Verdict verdict, Duration retryAfter) {

        static Outcome of(Verdict verdict) {
            return new Outcome(verdict, null);
        }
    }

    private final Administrators administrators;
    private final SecretCipher cipher;
    private final LoginAttemptLimiter limiter;
    private final Clock clock;

    AdministratorMfa(Administrators administrators, SecretCipher cipher, LoginAttemptLimiter limiter, Clock clock) {
        this.administrators = administrators;
        this.cipher = cipher;
        this.limiter = limiter;
        this.clock = clock;
    }

    /** The AAD of the encrypted secret: a cipher text copied to another row does not decrypt. */
    static String secretAad(PendingAdministrator who) {
        return "administrator-totp:" + who.id();
    }

    Outcome verify(PendingAdministrator who, String code, String clientIp) {
        String accountKey = ACCOUNT_KEY_PREFIX + who.id();
        // before any work: a locked account refuses even the right code. An admitted attempt is already counted as a
        // failure (see LoginAttemptLimiter) and spends one token of the IP shared with the logins.
        LoginAttemptLimiter.Admission admission = limiter.admit(accountKey, clientIp);
        if (!admission.allowed()) {
            return new Outcome(Verdict.LOCKED, admission.blockedFor());
        }
        try {
            Optional<Administrators.Totp> totp = administrators.findTotp(who.id());
            if (totp.isEmpty() || !totp.get().enabled()) {
                // not a code failure: give the place back
                limiter.release(accountKey, admission);
                return Outcome.of(Verdict.NOT_ENROLLED);
            }
            byte[] secret = cipher.decrypt(totp.get().secretCipher(), secretAad(who));
            OptionalLong step = Totp.matchingStep(secret, code, clock.instant());
            if (step.isEmpty() || administrators.acceptStep(who.id(), step.getAsLong()) == 0) {
                // a wrong code keeps the failure that was reserved for it
                return Outcome.of(Verdict.REJECTED);
            }
        } catch (DataAccessException | IllegalStateException e) {
            // database down or secret that does not decrypt: not a code failure. Only the class is logged: nothing
            // about the secret, the key or the code.
            limiter.release(accountKey, admission);
            log.error("MFA check unavailable ({})", e.getClass().getName());
            return Outcome.of(Verdict.UNAVAILABLE);
        } catch (RuntimeException e) {
            // an unexpected error is not a code failure either
            limiter.release(accountKey, admission);
            throw e;
        }
        limiter.recordSuccess(accountKey);
        return Outcome.of(Verdict.ACCEPTED);
    }
}
