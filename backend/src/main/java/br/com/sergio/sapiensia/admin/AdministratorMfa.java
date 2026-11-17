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
     * {@code retryAfter} is set only for {@link Verdict#LOCKED}; {@code codes} only for an accepted activation or
     * regeneration (shown once); {@code sessionVersion} only when the accepted action changed it (the new session must carry it).
     * {@code toString()} shows only the verdict.
     */
    record Outcome(
            Verdict verdict, Duration retryAfter, List<String> codes, OptionalInt sessionVersion, Started started) {

        Outcome {
            codes = List.copyOf(codes);
        }

        static Outcome of(Verdict verdict) {
            return new Outcome(verdict, null, List.of(), OptionalInt.empty(), null);
        }

        static Outcome locked(Duration retryAfter) {
            return new Outcome(Verdict.LOCKED, retryAfter, List.of(), OptionalInt.empty(), null);
        }

        static Outcome withCodes(List<String> codes) {
            return new Outcome(Verdict.ACCEPTED, null, codes, OptionalInt.empty(), null);
        }

        static Outcome acceptedAt(int sessionVersion) {
            return new Outcome(Verdict.ACCEPTED, null, List.of(), OptionalInt.of(sessionVersion), null);
        }

        /** An accepted start of the authenticator replacement: the new secret, for the answer and the session. */
        static Outcome startedReplacement(Started started) {
            return new Outcome(Verdict.ACCEPTED, null, List.of(), OptionalInt.empty(), started);
        }

        /** An accepted replacement: the new recovery codes and the session version that ends the other sessions. */
        static Outcome replaced(List<String> codes, int sessionVersion) {
            return new Outcome(Verdict.ACCEPTED, null, codes, OptionalInt.of(sessionVersion), null);
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
    private final RecentAuthentication recentAuthentication;

    AdministratorMfa(
            Administrators administrators,
            AdministratorRecoveryCodes recoveryCodes,
            SecretCipher cipher,
            LoginAttemptLimiter limiter,
            AuditLog audit,
            PlatformTransactionManager transactionManager,
            Clock clock,
            RecentAuthentication recentAuthentication) {
        this.administrators = administrators;
        this.recoveryCodes = recoveryCodes;
        this.cipher = cipher;
        this.limiter = limiter;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.recentAuthentication = recentAuthentication;
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
                return Outcome.withCodes(codes);
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

    /**
     * The login with a one-time recovery code instead of the TOTP. Same limiter and answers as {@link #verify}; a
     * malformed code is a wrong code. In one transaction, in this order: the session version goes up (which locks the
     * Administrator row, as the code regeneration does first, so the two never deadlock), the code is consumed by a
     * conditional UPDATE (0 rows: used meanwhile, so rejected and everything rolls back) and the event is audited. The
     * outcome carries the new session version, so every other session of the account ends (SEC-006).
     */
    Outcome recover(PendingAdministrator who, String code, String clientIp) {
        return guarded(who.id(), AuditAction.ADMIN_RECOVERY_CODE_LOGIN, clientIp, id -> {
            Optional<Administrators.StoredTotp> totp = administrators.findTotp(id);
            if (totp.isEmpty() || !totp.get().enabled()) {
                return Outcome.of(Verdict.NOT_ENROLLED);
            }
            Optional<UUID> match =
                    RecoveryCodes.normalize(code).flatMap(n -> RecoveryCodes.matching(recoveryCodes.findUnused(id), n));
            if (match.isEmpty()) {
                return Outcome.of(Verdict.REJECTED);
            }
            return transaction.execute(status -> {
                OptionalInt version = administrators.incrementSessionVersion(id);
                if (version.isEmpty()) {
                    return Outcome.of(Verdict.NOT_ENROLLED);
                }
                if (recoveryCodes.consume(match.get()) == 0) {
                    status.setRollbackOnly();
                    return Outcome.of(Verdict.REJECTED);
                }
                audit.recordInTransaction(
                        entry(AuditAction.ADMIN_RECOVERY_CODE_LOGIN, AuditResult.SUCCESS, id, clientIp));
                return Outcome.acceptedAt(version.getAsInt());
            });
        });
    }

    /**
     * A new set of recovery codes for the fully logged-in Administrator {@code id}, which also asks for a fresh TOTP
     * code (a stolen session alone cannot swap the codes, SEC-008). Same limiter and answers as {@link #verify}; the
     * code goes through the same replay guard, so the code of the login is refused. In one transaction, in this order:
     * the step is accepted (which locks the Administrator row first, as the recovery code login does), every code of
     * the account is replaced by 10 new ones (only their hashes) and the event is audited. The old codes die at once;
     * the new ones are in the outcome and nowhere else.
     */
    Outcome regenerateCodes(UUID administratorId, String code, String clientIp) {
        return guarded(administratorId, AuditAction.ADMIN_RECOVERY_CODES_REGENERATED, clientIp, id -> {
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
                List<String> codes = RecoveryCodes.generate();
                recoveryCodes.replaceAll(
                        id, codes.stream().map(RecoveryCodes::hash).toList());
                audit.recordInTransaction(
                        entry(AuditAction.ADMIN_RECOVERY_CODES_REGENERATED, AuditResult.SUCCESS, id, clientIp));
                return Outcome.withCodes(codes);
            });
        });
    }

    /**
     * Starts the replacement of the authenticator of the fully logged-in Administrator: a new secret, not stored
     * anywhere here (the caller keeps its cipher text in the session, to give back to {@link #confirmReplacement}). The
     * old authenticator stays valid until the confirmation. A session that began with a recovery code, while that login
     * is recent ({@link RecentAuthentication#oldCodeRequired}), needs no code; any other asks for the current TOTP code, through the same limiter and replay
     * guard as every code step (the step is spent in a transaction of its own), so the code of the login is refused. A
     * failed or locked attempt is audited as ADMIN_MFA_REPLACED; an accepted start changes nothing, so it is not.
     */
    Outcome startReplacement(AdministratorPrincipal who, String code, String clientIp) {
        if (!recentAuthentication.oldCodeRequired(who)) {
            try {
                if (!enrolled(who.id())) {
                    return Outcome.of(Verdict.NOT_ENROLLED);
                }
                return Outcome.startedReplacement(newReplacement(who));
            } catch (UnavailableException e) {
                return Outcome.of(Verdict.UNAVAILABLE);
            } catch (DataAccessException | IllegalStateException e) {
                unavailable(e);
                return Outcome.of(Verdict.UNAVAILABLE);
            }
        }
        return guarded(who.id(), AuditAction.ADMIN_MFA_REPLACED, clientIp, id -> {
            Optional<Administrators.StoredTotp> totp = administrators.findTotp(id);
            if (totp.isEmpty() || !totp.get().enabled()) {
                return Outcome.of(Verdict.NOT_ENROLLED);
            }
            byte[] secret = cipher.decrypt(totp.get().secretCipher(), secretAad(id));
            OptionalLong step = Totp.matchingStep(secret, code, clock.instant());
            if (step.isEmpty()) {
                return Outcome.of(Verdict.REJECTED);
            }
            return transaction.execute(status -> administrators.acceptStep(id, step.getAsLong()) == 0
                    ? Outcome.of(Verdict.REJECTED)
                    : Outcome.startedReplacement(newReplacement(who)));
        });
    }

    private Started newReplacement(AdministratorPrincipal who) {
        byte[] secret = Totp.newSecret();
        return new Started(
                new Enrollment(Totp.toBase32(secret), Totp.otpauthUri(ISSUER, who.email(), secret)),
                cipher.encrypt(secret, secretAad(who.id())));
    }

    /**
     * Confirms the replacement with a code of the new secret {@code pendingCipher} (the one started in this same
     * session; null when none was, which with an inactive MFA is NOT_ENROLLED, not a code failure). Same limiter and
     * answers as {@link #verify}. In one transaction, in this order: the session version goes up (locking the
     * Administrator row first, as the recovery code login and the code regeneration do), the secret is swapped by a
     * compare-and-set on the cipher text read (0 rows: another confirmation or an enrollment won, so everything rolls
     * back and it is NOT_ENROLLED), every recovery code is replaced by 10 new ones and the event is audited. The
     * outcome carries the new codes and the new session version (every other session ends, SEC-006).
     */
    Outcome confirmReplacement(AdministratorPrincipal who, String pendingCipher, String code, String clientIp) {
        return guarded(who.id(), AuditAction.ADMIN_MFA_REPLACED, clientIp, id -> {
            Optional<Administrators.StoredTotp> totp = administrators.findTotp(id);
            if (pendingCipher == null || totp.isEmpty() || !totp.get().enabled()) {
                return Outcome.of(Verdict.NOT_ENROLLED);
            }
            OptionalLong step = Totp.matchingStep(cipher.decrypt(pendingCipher, secretAad(id)), code, clock.instant());
            if (step.isEmpty()) {
                return Outcome.of(Verdict.REJECTED);
            }
            return transaction.execute(status -> {
                OptionalInt version = administrators.incrementSessionVersion(id);
                if (version.isEmpty()) {
                    return Outcome.of(Verdict.NOT_ENROLLED);
                }
                if (administrators.replaceTotp(id, totp.get().secretCipher(), pendingCipher, step.getAsLong()) == 0) {
                    status.setRollbackOnly();
                    return Outcome.of(Verdict.NOT_ENROLLED);
                }
                List<String> codes = RecoveryCodes.generate();
                recoveryCodes.replaceAll(
                        id, codes.stream().map(RecoveryCodes::hash).toList());
                audit.recordInTransaction(entry(AuditAction.ADMIN_MFA_REPLACED, AuditResult.SUCCESS, id, clientIp));
                return Outcome.replaced(codes, version.getAsInt());
            });
        });
    }

    /**
     * The TOTP step of a reauthentication of the fully logged-in Administrator {@code id}, after the password was
     * checked. Same limiter key and answers as {@link #verify}, and the same replay guard: the step is accepted
     * (conditional UPDATE) and audited in one transaction, so the code of the login, or of an earlier
     * reauthentication, is refused. Nothing else is written.
     */
    Outcome reauthenticate(UUID administratorId, String code, String clientIp) {
        return guarded(administratorId, AuditAction.ADMIN_REAUTHENTICATION, clientIp, id -> {
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
                audit.recordInTransaction(entry(AuditAction.ADMIN_REAUTHENTICATION, AuditResult.SUCCESS, id, clientIp));
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
