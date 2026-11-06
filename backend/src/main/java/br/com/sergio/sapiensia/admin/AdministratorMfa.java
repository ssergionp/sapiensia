package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.customer.LoginAttemptLimiter;
import br.com.sergio.sapiensia.security.SecretCipher;
import br.com.sergio.sapiensia.security.Totp;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Checks the TOTP code of a pending Administrator: the login limiter (its own account key, the IP bucket shared with
 * the logins), the decrypted secret, the code of the steps t-1..t+1, and the replay guard (a step is accepted only
 * once, and only if greater than the last accepted one). Also enrolls the authenticator, only while MFA is inactive.
 */
@Service
class AdministratorMfa {

    private static final Logger log = LoggerFactory.getLogger(AdministratorMfa.class);

    // the account's code failures; from the session principal, never from a typed field, so nothing to normalize. The
    // prefix keeps it apart from "admin:<e-mail>" and "customer:<e-mail>".
    private static final String ACCOUNT_KEY_PREFIX = "admin-mfa:";

    private static final String ISSUER = "SapiensIA";

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

    /** What the authenticator app needs; {@code toString()} hides both, since the URI carries the secret. */
    record Enrollment(String secret, String otpauthUri) {

        @Override
        public String toString() {
            return "Enrollment[redacted]";
        }
    }

    /** One code check; its verdict is never LOCKED or UNAVAILABLE (those come from {@link #guarded}). */
    private interface Check {
        Verdict run(UUID id);
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

    boolean enrolled(UUID id) {
        return administrators.findTotp(id).map(Administrators.Totp::enabled).orElse(false);
    }

    /**
     * Stores a new pending secret and returns it, or empty when MFA is already active (409): otherwise someone who
     * only knows the password could swap the authenticator. The conditional UPDATE is the real guard.
     */
    Optional<Enrollment> startEnrollment(PendingAdministrator who) {
        byte[] secret = Totp.newSecret();
        if (administrators.startEnrollment(who.id(), cipher.encrypt(secret, secretAad(who))) == 0) {
            return Optional.empty();
        }
        return Optional.of(new Enrollment(Totp.toBase32(secret), Totp.otpauthUri(ISSUER, who.email(), secret)));
    }

    /**
     * Activates MFA when {@code code} matches the pending secret. Same limiter key and IP budget as {@link #verify}; a
     * wrong code stays counted, an inactive or missing enrollment (NOT_ENROLLED) is not a code failure.
     */
    Outcome confirmEnrollment(PendingAdministrator who, String code, String clientIp) {
        return guarded(who, clientIp, id -> {
            Optional<Administrators.Totp> totp = administrators.findTotp(id);
            if (totp.isEmpty() || totp.get().enabled() || totp.get().secretCipher() == null) {
                return Verdict.NOT_ENROLLED;
            }
            String secretCipher = totp.get().secretCipher();
            OptionalLong step = Totp.matchingStep(cipher.decrypt(secretCipher, secretAad(who)), code, clock.instant());
            if (step.isEmpty()) {
                return Verdict.REJECTED;
            }
            // 0 rows: activated or re-enrolled meanwhile, so not a code failure
            return administrators.enable(id, secretCipher, step.getAsLong()) == 0
                    ? Verdict.NOT_ENROLLED
                    : Verdict.ACCEPTED;
        });
    }

    Outcome verify(PendingAdministrator who, String code, String clientIp) {
        return guarded(who, clientIp, id -> {
            Optional<Administrators.Totp> totp = administrators.findTotp(id);
            if (totp.isEmpty() || !totp.get().enabled()) {
                return Verdict.NOT_ENROLLED;
            }
            byte[] secret = cipher.decrypt(totp.get().secretCipher(), secretAad(who));
            OptionalLong step = Totp.matchingStep(secret, code, clock.instant());
            return step.isEmpty() || administrators.acceptStep(id, step.getAsLong()) == 0
                    ? Verdict.REJECTED
                    : Verdict.ACCEPTED;
        });
    }

    /**
     * The limiter around a code check, shared by the login code and the enrollment confirmation (same account key,
     * same IP budget). Before any work: a locked account refuses even the right code. An admitted attempt is already
     * counted as a failure (see LoginAttemptLimiter) and spends one token of the shared IP; only a non-code outcome
     * (NOT_ENROLLED, database down, secret that does not decrypt, unexpected error) gives the place back, and a wrong
     * code keeps its failure.
     */
    private Outcome guarded(PendingAdministrator who, String clientIp, Check check) {
        String accountKey = ACCOUNT_KEY_PREFIX + who.id();
        LoginAttemptLimiter.Admission admission = limiter.admit(accountKey, clientIp);
        if (!admission.allowed()) {
            return new Outcome(Verdict.LOCKED, admission.blockedFor());
        }
        Verdict verdict;
        try {
            verdict = check.run(who.id());
        } catch (DataAccessException | IllegalStateException e) {
            // only the class is logged: nothing about the secret, the key or the code
            limiter.release(accountKey, admission);
            log.error("MFA check unavailable ({})", e.getClass().getName());
            return Outcome.of(Verdict.UNAVAILABLE);
        } catch (RuntimeException e) {
            limiter.release(accountKey, admission);
            throw e;
        }
        switch (verdict) {
            case NOT_ENROLLED -> limiter.release(accountKey, admission);
            case ACCEPTED -> limiter.recordSuccess(accountKey);
            default -> {}
        }
        return Outcome.of(verdict);
    }
}
