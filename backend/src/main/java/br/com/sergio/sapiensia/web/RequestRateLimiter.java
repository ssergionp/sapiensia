package br.com.sergio.sapiensia.web;

import br.com.sergio.sapiensia.customer.Emails;
import br.com.sergio.sapiensia.customer.IpLimitKey;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory request limits (SEC-014): per operation, a fixed window per client IP and, where the operation has one,
 * a fixed window per account key. State is per instance and is lost on restart (ADR-0004).
 *
 * <p>{@link #tryConsume} takes one token from both buckets only when both have one. Each bucket is atomic by itself
 * (Bucket4j), so there is no lock across buckets: both are charged, and if either refuses, the one that was charged is
 * given its token back. Two requests racing for the last token of a shared bucket therefore cannot both pass; the worst
 * case is a request refused while another refused request still holds a token it is about to return.
 *
 * <p>An operation with no account limit (no {@code account-*} properties) ignores the account key. Each operation has
 * its own table of tracked keys, capped at 20 000: a full table refuses only requests of that operation that need a
 * key not tracked yet (never evicting, which would reset a counter), so an attack on one operation cannot lock the
 * others out. Keys hold an IP or an e-mail hash and are never logged.
 */
@Component
public class RequestRateLimiter {

    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(1);
    private static final int DEFAULT_MAX_TRACKED_KEYS_PER_OPERATION = 20_000;

    private static final Logger log = LoggerFactory.getLogger(RequestRateLimiter.class);

    /**
     * Charges one request or throws {@link RateLimitedException} (429). The log says only the operation: never the
     * IP, the account or the e-mail. It is sampled, one line per operation per minute with the count of the held ones,
     * and a full table has its own line; the refusal itself is never sampled.
     */
    public void require(RateLimitedOperation operation, String ipKey, Optional<String> accountKey) {
        refusal(operation, ipKey, accountKey).ifPresent(refusal -> {
            if (refusal.tableFull()) {
                refusalLog
                        .pass("rate-limit-full:" + operation)
                        .ifPresent(held -> log.warn("Request limit table full for {}: suppressed={}", operation, held));
            } else {
                refusalLog
                        .pass("rate-limit:" + operation)
                        .ifPresent(held -> log.warn("Request limit reached for {}: suppressed={}", operation, held));
            }
            throw new RateLimitedException(refusal.retryAfter());
        });
    }

    /** The key of an account identified by e-mail: the SHA-256 (hex) of the normalized e-mail, never the e-mail. */
    public static String emailKey(String email) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(Emails.normalize(email).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** A refusal: how long to wait, and whether it is because the table of the operation is full. */
    private record Refusal(Duration retryAfter, boolean tableFull) {}

    /** A bucket and its capacity, to tell an idle (full) one when purging. */
    private record Tracked(Bucket bucket, long capacity) {}

    private final RateLimitProperties properties;
    private final Clock clock;
    private final RefusalLogThrottle refusalLog;
    private final int maxTrackedKeysPerOperation;
    private final TimeMeter timeMeter;
    private final Map<RateLimitedOperation, ConcurrentHashMap<String, Tracked>> tables =
            new EnumMap<>(RateLimitedOperation.class);
    private final AtomicReference<Instant> lastPurge;

    @Autowired
    RequestRateLimiter(RateLimitProperties properties, Clock clock, RefusalLogThrottle refusalLog) {
        this(properties, clock, DEFAULT_MAX_TRACKED_KEYS_PER_OPERATION, refusalLog);
    }

    RequestRateLimiter(RateLimitProperties properties, Clock clock) {
        this(properties, clock, DEFAULT_MAX_TRACKED_KEYS_PER_OPERATION);
    }

    RequestRateLimiter(RateLimitProperties properties, Clock clock, int maxTrackedKeysPerOperation) {
        this(properties, clock, maxTrackedKeysPerOperation, new RefusalLogThrottle(Duration.ZERO, clock));
    }

    RequestRateLimiter(
            RateLimitProperties properties,
            Clock clock,
            int maxTrackedKeysPerOperation,
            RefusalLogThrottle refusalLog) {
        this.properties = properties;
        this.refusalLog = refusalLog;
        this.clock = clock;
        this.maxTrackedKeysPerOperation = maxTrackedKeysPerOperation;
        for (RateLimitedOperation operation : RateLimitedOperation.values()) {
            tables.put(operation, new ConcurrentHashMap<>());
        }
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
     * Counts one request. Empty means it may proceed; otherwise the time until the next token of the slowest refused
     * bucket, in whole seconds rounded up and never below one second.
     */
    public Optional<Duration> tryConsume(RateLimitedOperation operation, String ipKey, Optional<String> accountKey) {
        return refusal(operation, ipKey, accountKey).map(Refusal::retryAfter);
    }

    private Optional<Refusal> refusal(RateLimitedOperation operation, String ipKey, Optional<String> accountKey) {
        purgeIfDue();
        RateLimitProperties.Limit limit = properties.limit(operation);
        ConcurrentHashMap<String, Tracked> table = tables.get(operation);
        String ipBucketKey = operation + "|ip|" + IpLimitKey.of(ipKey);
        boolean hasAccount = limit.accountCapacity() != null && accountKey.isPresent();
        String accountBucketKey = hasAccount ? operation + "|account|" + accountKey.get() : null;

        // IP first: a request the IP refuses must never create an account key (else one IP could fill the table)
        if (isNew(table, ipBucketKey) && table.size() + 1 > maxTrackedKeysPerOperation) {
            return Optional.of(new Refusal(roundUp(PURGE_INTERVAL), true));
        }
        int ipCapacity = Math.max(limit.ipCapacity(), properties.ipCapacityFloor());
        Bucket ipBucket = bucket(table, ipBucketKey, ipCapacity, limit.ipWindow());
        ConsumptionProbe ip = ipBucket.tryConsumeAndReturnRemaining(1);
        if (!ip.isConsumed()) {
            long nanos = ip.getNanosToWaitForRefill();
            Tracked existing = hasAccount ? table.get(accountBucketKey) : null;
            if (existing != null) {
                // an account that is already tracked may need longer: tell the longer wait (nothing is created or
                // spent)
                nanos = Math.max(
                        nanos, existing.bucket().estimateAbilityToConsume(1).getNanosToWaitForRefill());
            }
            return Optional.of(new Refusal(roundUp(Duration.ofNanos(nanos)), false));
        }
        if (!hasAccount) {
            return Optional.empty();
        }
        if (isNew(table, accountBucketKey) && table.size() + 1 > maxTrackedKeysPerOperation) {
            ipBucket.addTokens(1);
            return Optional.of(new Refusal(roundUp(PURGE_INTERVAL), true));
        }
        Bucket accountBucket = bucket(table, accountBucketKey, limit.accountCapacity(), limit.accountWindow());
        ConsumptionProbe account = accountBucket.tryConsumeAndReturnRemaining(1);
        if (account.isConsumed()) {
            return Optional.empty();
        }
        // a refusal in the account bucket must not spend the IP's token
        ipBucket.addTokens(1);
        return Optional.of(new Refusal(roundUp(Duration.ofNanos(account.getNanosToWaitForRefill())), false));
    }

    int trackedKeys() {
        return tables.values().stream().mapToInt(ConcurrentHashMap::size).sum();
    }

    private static boolean isNew(ConcurrentHashMap<String, Tracked> table, String key) {
        return !table.containsKey(key);
    }

    private static Duration roundUp(Duration wait) {
        long seconds = (wait.toNanos() + 999_999_999L) / 1_000_000_000L;
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    private Bucket bucket(ConcurrentHashMap<String, Tracked> table, String key, int capacity, Duration window) {
        return table.computeIfAbsent(
                        key,
                        k -> new Tracked(
                                Bucket.builder()
                                        // all tokens come back together when the window ends
                                        .addLimit(limit ->
                                                limit.capacity(capacity).refillIntervally(capacity, window))
                                        .withCustomTimePrecision(timeMeter)
                                        .build(),
                                capacity))
                .bucket();
    }

    /** Drops full (idle) buckets, at most once per minute, from inside the regular calls. */
    private void purgeIfDue() {
        Instant now = clock.instant();
        Instant last = lastPurge.get();
        if (now.isBefore(last.plus(PURGE_INTERVAL)) || !lastPurge.compareAndSet(last, now)) {
            return;
        }
        // Benign race: a bucket may be removed right after computeIfAbsent returned it; at most one token is lost.
        for (ConcurrentHashMap<String, Tracked> table : tables.values()) {
            for (String key : table.keySet()) {
                table.computeIfPresent(
                        key,
                        (k, tracked) -> tracked.bucket().getAvailableTokens() >= tracked.capacity() ? null : tracked);
            }
        }
    }
}
