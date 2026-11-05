package br.com.sergio.sapiensia.customer;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory login throttling: a lockout per account (SEC-002) and a fixed window per client IP (SEC-014).
 * State is per instance and is lost on restart. Each key is updated atomically through ConcurrentHashMap.
 *
 * <p>An attempt reserves its place before the password is hashed: {@link #admit} counts it as a failure right away,
 * so parallel requests cannot all pass the check before the first failure is recorded. The caller then ends the
 * attempt with {@link #recordSuccess} (a correct login clears the account) or, if the attempt is interrupted by an
 * unexpected error, with {@link #release}. A wrong password needs no further call: it is already counted.
 *
 * <p>Both maps are capped. When one is full, an attempt for a key that is not tracked yet is refused instead of
 * evicting another entry: evicting would reset a counter, and refusing never does. The cap is checked before the
 * insert, so concurrent attempts can overshoot it by at most the number of request threads.
 */
@Component
class LoginAttemptLimiter {

    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(1);
    private static final int DEFAULT_MAX_TRACKED_KEYS = 100_000;

    /** Result of {@link #admit}: {@code blockedFor} is null when the attempt may proceed. */
    record Admission(Duration blockedFor, Instant reservedAt) {
        boolean allowed() {
            return blockedFor == null;
        }
    }

    /** Recent attempts and the end of the current lock (null when not locked). */
    private record AccountState(List<Instant> failures, Instant lockedUntil) {}

    private final LoginLimitProperties properties;
    private final Clock clock;
    private final int maxTrackedKeys;
    private final TimeMeter timeMeter;
    private final ConcurrentHashMap<String, AccountState> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> ips = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastPurge;

    @Autowired
    LoginAttemptLimiter(LoginLimitProperties properties, Clock clock) {
        this(properties, clock, DEFAULT_MAX_TRACKED_KEYS);
    }

    LoginAttemptLimiter(LoginLimitProperties properties, Clock clock, int maxTrackedKeys) {
        this.properties = properties;
        this.clock = clock;
        this.maxTrackedKeys = maxTrackedKeys;
        this.lastPurge = new AtomicReference<>(clock.instant());
        this.timeMeter = new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                Instant now = clock.instant();
                return now.getEpochSecond() * 1_000_000_000L + now.getNano();
            }

            @Override
            public boolean isWallClockBased() {
                return true;
            }
        };
    }

    /**
     * Decides whether one login attempt may proceed. It spends one IP token, and, when the attempt is admitted, reserves
     * one failure of the account (locking it on the {@code accountMaxFailures}-th). A refused attempt reserves nothing
     * and does not extend a lock.
     */
    Admission admit(String email, String clientIp) {
        purgeIfDue();
        Instant now = clock.instant();
        String accountKey = key(email);
        String ipKey = clientIp == null ? "" : clientIp;

        boolean accountsFull = !accounts.containsKey(accountKey) && accounts.size() >= maxTrackedKeys;
        boolean ipsFull = !ips.containsKey(ipKey) && ips.size() >= maxTrackedKeys;
        if (accountsFull || ipsFull) {
            return refused(PURGE_INTERVAL);
        }

        Duration wait = Duration.ZERO;
        ConsumptionProbe probe = ips.computeIfAbsent(ipKey, k -> newBucket()).tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            wait = Duration.ofNanos(probe.getNanosToWaitForRefill());
        }

        AtomicReference<Duration> lockRemaining = new AtomicReference<>(Duration.ZERO);
        boolean reserve = wait.isZero();
        accounts.compute(accountKey, (k, state) -> {
            if (state != null && state.lockedUntil() != null) {
                if (now.isBefore(state.lockedUntil())) {
                    lockRemaining.set(Duration.between(now, state.lockedUntil()));
                    return state;
                }
                state = null; // the lock is over: counting restarts
            }
            if (!reserve) {
                return state;
            }
            List<Instant> failures = state == null ? new ArrayList<>() : new ArrayList<>(recent(state, now));
            failures.add(now);
            Instant lockedUntil =
                    failures.size() >= properties.accountMaxFailures() ? now.plus(properties.accountLock()) : null;
            return new AccountState(List.copyOf(failures), lockedUntil);
        });

        if (lockRemaining.get().compareTo(wait) > 0) {
            wait = lockRemaining.get();
        }
        if (wait.isZero()) {
            return new Admission(null, now);
        }
        return refused(wait);
    }

    /** A correct login clears the failures and any lock of the account. */
    void recordSuccess(String email) {
        releaseAccount(email);
    }

    /**
     * Clears the failures and any lock of the account, leaving the per-IP limit untouched. Used after a password reset
     * through the e-mailed link: whoever changed the password proved they own the account's e-mail.
     */
    void releaseAccount(String email) {
        purgeIfDue();
        accounts.remove(key(email));
    }

    /** Gives back the place reserved by an admitted attempt that ended without a verdict (unexpected error). */
    void release(String email, Admission admission) {
        Instant reservedAt = admission.reservedAt();
        accounts.computeIfPresent(key(email), (k, state) -> {
            List<Instant> failures = new ArrayList<>(state.failures());
            if (!failures.remove(reservedAt)) {
                return state; // already purged or cleared by a login that worked
            }
            Instant lockedUntil =
                    reservedAt.plus(properties.accountLock()).equals(state.lockedUntil()) ? null : state.lockedUntil();
            return failures.isEmpty() && lockedUntil == null
                    ? null
                    : new AccountState(List.copyOf(failures), lockedUntil);
        });
    }

    int trackedAccounts() {
        return accounts.size();
    }

    int trackedIps() {
        return ips.size();
    }

    private static Admission refused(Duration wait) {
        long seconds = (wait.toNanos() + 999_999_999L) / 1_000_000_000L;
        return new Admission(Duration.ofSeconds(Math.max(1, seconds)), null);
    }

    private Bucket newBucket() {
        return Bucket.builder()
                // all tokens come back together when the window ends: at most ipMaxAttempts per window
                .addLimit(limit -> limit.capacity(properties.ipMaxAttempts())
                        .refillIntervally(properties.ipMaxAttempts(), properties.ipWindow()))
                .withCustomTimePrecision(timeMeter)
                .build();
    }

    private List<Instant> recent(AccountState state, Instant now) {
        return state.failures().stream()
                .filter(failure -> failure.plus(properties.accountWindow()).isAfter(now))
                .toList();
    }

    private static String key(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Drops idle entries, at most once per minute, from inside the regular calls. Never drops a locked account. */
    private void purgeIfDue() {
        Instant now = clock.instant();
        Instant last = lastPurge.get();
        if (now.isBefore(last.plus(PURGE_INTERVAL)) || !lastPurge.compareAndSet(last, now)) {
            return;
        }
        for (String account : accounts.keySet()) {
            accounts.computeIfPresent(account, (k, state) -> {
                boolean locked = state.lockedUntil() != null && now.isBefore(state.lockedUntil());
                return locked || !recent(state, now).isEmpty() ? state : null;
            });
        }
        // Benign race: a bucket may be removed right after computeIfAbsent returned it; at most one token is lost.
        for (String ip : ips.keySet()) {
            ips.computeIfPresent(
                    ip, (k, bucket) -> bucket.getAvailableTokens() >= properties.ipMaxAttempts() ? null : bucket);
        }
    }
}
