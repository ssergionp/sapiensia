package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import br.com.sergio.sapiensia.customer.LoginAttemptLimiter;
import br.com.sergio.sapiensia.security.SecretCipher;
import br.com.sergio.sapiensia.security.Totp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks the TOTP code of a pending Administrator: the login limiter (its own account key, the IP bucket shared with
 * the logins), the decrypted secret, the code of the steps t-1..t+1, and the replay guard (a step is accepted only
 * once, and only if greater than the last accepted one). Also enrolls the authenticator, only while MFA is inactive,
 * and hands out the one-time recovery codes. Every accepted action writes its audit event in its own transaction
 * (no trail, no action); a wrong code or a lock is audited apart, so a rollback does not erase it.
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

    /**
     * {@code retryAfter} is set only for {@link Verdict#LOCKED}; {@code codes} only for an accepted activation (shown
     * once); {@code sessionVersion} only when the accepted action changed it (the new session must carry it).
     * {@code toString()} shows only the verdict.
     */
    record Outcome(Verdict verdict, Duration retryAfter, List<String> codes, OptionalInt sessionVersion) {

        Outcome {
            codes = List.copyOf(codes);
        }

        static Outcome of(Verdict verdict) {
            return new Outcome(verdict, null, List.of(), OptionalInt.empty());
        }

        static Outcome locked(Duration retryAfter) {
            return new Outcome(Verdict.LOCKED, retryAfter, List.of(), OptionalInt.empty());
        }

        static Outcome enabledWith(List<String> codes) {
            return new Outcome(Verdict.ACCEPTED, null, codes, OptionalInt.empty());
        }

        static Outcome acceptedAt(int sessionVersion) {
            return new Outcome(Verdict.ACCEPTED, null, List.of(), OptionalInt.of(sessionVersion));
        }

        @Override
        public String toString() {
            return "Outcome[verdict=" + verdict + "]";
        }
    }

    /** What the authenticator app needs; {@code toString()} hides both, since the URI carries the secret. */
    record Enrollment(String secret, String otpauthUri) {

        @Override
        public String toString() {
            return "Enrollment[redacted]";
        }
    }

    /**
     * A started enrollment: what goes to the authenticator app, and the cipher text stored for it, which the caller
     * keeps server-side in the same pending session and gives back to {@link #confirmEnrollment}. {@code toString()}
     * hides both.
     */
    record Started(Enrollment enrollment, String secretCipher) {

        @Override
        public String toString() {
            return "Started[redacted]";
        }
    }

    /**
     * One code check of the Administrator {@code id}; its verdict is never LOCKED or UNAVAILABLE (those come from
     * {@link #guarded}). A check that writes runs its writes and the SUCCESS audit event in one transaction of its own
     * (the {@code transaction} template), so a failure propagates out of it and reaches {@link #guarded}.
     */
    private interface Check {
        Outcome run(UUID id);
    }

    private final Administrators administrators;
    private final AdministratorRecoveryCodes recoveryCodes;
    private final SecretCipher cipher;
    private final LoginAttemptLimiter limiter;
    private final AuditLog audit;
    private final TransactionTemplate transaction;
    private final Clock clock;

    AdministratorMfa(
            Administrators administrators,
            AdministratorRecoveryCodes recoveryCodes,
            SecretCipher cipher,
            LoginAttemptLimiter limiter,
            AuditLog audit,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.administrators = administrators;
        this.recoveryCodes = recoveryCodes;
        this.cipher = cipher;
        this.limiter = limiter;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** The AAD of the encrypted secret: a cipher text copied to another row does not decrypt. */
    static String secretAad(UUID id) {
        return "administrator-totp:" + id;
    }

    /** Thrown when the database or the cipher fails outside a code check; the controller answers 503. */
    static class UnavailableException extends RuntimeException {
        UnavailableException() {
            super("MFA unavailable");
        }
    }

    // only the class is logged: nothing about the secret, the key or the code
    private static UnavailableException unavailable(RuntimeException e) {
        log.error("MFA check unavailable ({})", e.getClass().getName());
        return new UnavailableException();
    }

    boolean enrolled(UUID id) {
        try {
            return administrators
                    .findTotp(id)
                    .map(Administrators.StoredTotp::enabled)
                    .orElse(false);
        } catch (DataAccessException | IllegalStateException e) {
            throw unavailable(e);
        }
    }

    /**
     * Stores a new pending secret and returns it, or empty when MFA is already active (409): otherwise someone who
     * only knows the password could swap the authenticator. The conditional UPDATE is the real guard.
     */
    Optional<Started> startEnrollment(PendingAdministrator who) {
        byte[] secret = Totp.newSecret();
        String secretCipher;
        try {
            secretCipher = cipher.encrypt(secret, secretAad(who.id()));
            if (administrators.startEnrollment(who.id(), secretCipher) == 0) {
                return Optional.empty();
            }
        } catch (DataAccessException | IllegalStateException e) {
            throw unavailable(e);
        }
        return Optional.of(new Started(
                new Enrollment(Totp.toBase32(secret), Totp.otpauthUri(ISSUER, who.email(), secret)), secretCipher));
    }

    /**
     * Activates MFA when {@code code} matches the secret of the enrollment started in this same pending session
     * ({@code startedCipher}, null when none was). A secret abandoned in another session, or replaced since by
     * another enrollment, is worth nothing. Same limiter key and IP budget as {@link #verify}; a wrong code stays
     * counted, an active MFA or a missing or replaced enrollment (NOT_ENROLLED) is not a code failure. The activation,
     * the 10 new recovery codes (only their hashes) and the audit event commit together; the codes are in the outcome
     * and nowhere else.
     */
    Outcome confirmEnrollment(PendingAdministrator who, String startedCipher, String code, String clientIp) {
        return guarded(who.id(), AuditAction.ADMIN_MFA_ENABLED, clientIp, id -> {
            Optional<Administrators.StoredTotp> totp = administrators.findTotp(id);
            if (startedCipher == null
                    || totp.isEmpty()
                    || totp.get().enabled()
                    || !startedCipher.equals(totp.get().secretCipher())) {
                return Outcome.of(Verdict.NOT_ENROLLED);
            }
            OptionalLong step = Totp.matchingStep(cipher.decrypt(startedCipher, secretAad(id)), code, clock.instant());
            if (step.isEmpty()) {
                return Outcome.of(Verdict.REJECTED);
            }
            return transaction.execute(status -> {
                // 0 rows: activated or re-enrolled meanwhile, so not a code failure
                if (administrators.enable(id, startedCipher, step.getAsLong()) == 0) {
                    return Outcome.of(Verdict.NOT_ENROLLED);
                }
                List<String> codes = RecoveryCodes.generate();
                recoveryCodes.replaceAll(
                        id, codes.stream().map(RecoveryCodes::hash).toList());
                audit.recordInTransaction(entry(AuditAction.ADMIN_MFA_ENABLED, AuditResult.SUCCESS, id, clientIp));
                return Outcome.enabledWith(codes);
            });
        });
    }

    /** The TOTP login: the step is accepted (replay guard) and audited in one transaction. */
    Outcome verify(PendingAdministrator who, String code, String clientIp) {
        return guarded(who.id(), AuditAction.ADMIN_MFA_LOGIN, clientIp, id -> {
            Optional<Administrators.StoredTotp> totp = administrators.findTotp(id);
            if (totp.isEmpty() || !totp.get().enabled()) {
                return Outcome.of(Verdict.NOT_ENROLLED);
            }
            byte[] secret = cipher.decrypt(totp.get().secretCipher(), secretAad(id));
            OptionalLong step = Totp.matchingStep(secret, code, clock.instant());
            if (step.isEmpty()) {
                return Outcome.of(Verdict.REJECTED);
            }
            return transaction.execute(status -> {
                if (administrators.acceptStep(id, step.getAsLong()) == 0) {
                    return Outcome.of(Verdict.REJECTED);
                }
                audit.recordInTransaction(entry(AuditAction.ADMIN_MFA_LOGIN, AuditResult.SUCCESS, id, clientIp));
                return Outcome.of(Verdict.ACCEPTED);
            });
        });
    }

    private static AuditEntry entry(AuditAction action, AuditResult result, UUID id, String clientIp) {
        return new AuditEntry(action, result, id, AuditObject.ADMINISTRATOR, id, clientIp);
    }

    /**
     * The limiter around a code check of the Administrator {@code id}, shared by every code step (same account key,
     * same IP budget), and the audit of its failures under {@code action}. Before any work: a locked account refuses
     * even the right code (BLOCKED). An admitted attempt is already counted as a failure (see LoginAttemptLimiter) and
     * spends one token of the shared IP; only a non-code outcome (NOT_ENROLLED, database down, secret that does not
     * decrypt, failed audit, unexpected error) gives the place back, and a wrong code keeps its failure (FAILURE).
     * Neither UNAVAILABLE nor NOT_ENROLLED is audited: they are not code failures, and the database may be what is
     * down. The check's own transaction has ended (committed or rolled back) before any exception reaches this method.
     */
    private Outcome guarded(UUID id, AuditAction action, String clientIp, Check check) {
        String accountKey = ACCOUNT_KEY_PREFIX + id;
        LoginAttemptLimiter.Admission admission = limiter.admit(accountKey, clientIp);
        if (!admission.allowed()) {
            audit.recordIndependent(entry(action, AuditResult.BLOCKED, id, clientIp));
            return Outcome.locked(admission.blockedFor());
        }
        Outcome outcome;
        try {
            outcome = check.run(id);
        } catch (DataAccessException | TransactionException | IllegalStateException e) {
            limiter.release(accountKey, admission);
            unavailable(e);
            return Outcome.of(Verdict.UNAVAILABLE);
        } catch (RuntimeException e) {
            limiter.release(accountKey, admission);
            throw e;
        }
        switch (outcome.verdict()) {
            case NOT_ENROLLED -> limiter.release(accountKey, admission);
            case ACCEPTED -> limiter.recordSuccess(accountKey);
            case REJECTED -> audit.recordIndependent(entry(action, AuditResult.FAILURE, id, clientIp));
            default -> {}
        }
        return outcome;
    }
}
