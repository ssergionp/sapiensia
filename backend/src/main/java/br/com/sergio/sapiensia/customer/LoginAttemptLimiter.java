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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * In-memory login throttling: a lockout per account (SEC-002) and a token bucket per client IP (SEC-014).
 * State is per instance and is lost on restart. Each key is updated atomically through ConcurrentHashMap.
 */
@Component
class LoginAttemptLimiter {

    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(1);

    /** Recent failure times and the end of the current lock (null when not locked). */
    private record AccountState(List<Instant> failures, Instant lockedUntil) {}

    private final LoginLimitProperties properties;
    private final Clock clock;
    private final TimeMeter timeMeter;
    private final ConcurrentHashMap<String, AccountState> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> ips = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastPurge;

    LoginAttemptLimiter(LoginLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
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

    /** Empty means the attempt may proceed; otherwise how long the caller must wait. Spends one IP token. */
    Optional<Duration> blockedFor(String email, String clientIp) {
        purgeIfDue();
        Instant now = clock.instant();

        Duration wait = Duration.ZERO;
        ConsumptionProbe probe = ips.computeIfAbsent(clientIp == null ? "" : clientIp, key -> newBucket())
                .tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            wait = Duration.ofNanos(probe.getNanosToWaitForRefill());
        }

        AccountState state = accounts.get(key(email));
        if (state != null && state.lockedUntil() != null && now.isBefore(state.lockedUntil())) {
            Duration remaining = Duration.between(now, state.lockedUntil());
            if (remaining.compareTo(wait) > 0) {
                wait = remaining;
            }
        }

        if (wait.isZero()) {
            return Optional.empty();
        }
        long seconds = (wait.toNanos() + 999_999_999L) / 1_000_000_000L;
        return Optional.of(Duration.ofSeconds(Math.max(1, seconds)));
    }

    void recordFailure(String email) {
        purgeIfDue();
        Instant now = clock.instant();
        accounts.compute(key(email), (k, state) -> {
            if (state != null && state.lockedUntil() != null && now.isBefore(state.lockedUntil())) {
                return state;
            }
            List<Instant> failures = state == null ? new ArrayList<>() : new ArrayList<>(recent(state, now));
            failures.add(now);
            if (failures.size() >= properties.accountMaxFailures()) {
                return new AccountState(List.of(), now.plus(properties.accountLock()));
            }
            return new AccountState(failures, null);
        });
    }

    void recordSuccess(String email) {
        purgeIfDue();
        accounts.remove(key(email));
    }

    /** Number of tracked accounts and IPs; exposed for the memory test. */
    int trackedKeys() {
        return accounts.size() + ips.size();
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(properties.ipMaxAttempts())
                        .refillGreedy(properties.ipMaxAttempts(), properties.ipWindow()))
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

    /** Drops idle entries, at most once per minute, from inside the regular calls. */
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
        for (String ip : ips.keySet()) {
            ips.computeIfPresent(
                    ip, (k, bucket) -> bucket.getAvailableTokens() >= properties.ipMaxAttempts() ? null : bucket);
        }
    }
}
