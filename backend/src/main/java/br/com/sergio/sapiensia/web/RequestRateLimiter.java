package br.com.sergio.sapiensia.web;

import br.com.sergio.sapiensia.customer.Emails;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
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
 * <p>An operation with no account limit (no {@code account-*} properties) ignores the account key. The map is capped
 * like the login limiter's: when it is full, a request that needs a key not tracked yet is refused (never evicting,
 * which would reset a counter). Keys hold an IP or an e-mail hash and are never logged.
 */
@Component
public class RequestRateLimiter {

    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(1);
    private static final int DEFAULT_MAX_TRACKED_KEYS = 100_000;

    private static final Logger log = LoggerFactory.getLogger(RequestRateLimiter.class);

    /**
     * Charges one request or throws {@link RateLimitedException} (429). The log says only the operation: never the
     * IP, the account or the e-mail.
     */
    public void require(RateLimitedOperation operation, String ipKey, Optional<String> accountKey) {
        tryConsume(operation, ipKey, accountKey).ifPresent(wait -> {
            log.warn("Request limit reached for {}", operation);
            throw new RateLimitedException(wait);
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

    /** A bucket and its capacity, to tell an idle (full) one when purging. */
    private record Tracked(Bucket bucket, long capacity) {}

    private final RateLimitProperties properties;
    private final Clock clock;
    private final int maxTrackedKeys;
    private final TimeMeter timeMeter;
    private final ConcurrentHashMap<String, Tracked> buckets = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastPurge;

    @Autowired
    RequestRateLimiter(RateLimitProperties properties, Clock clock) {
        this(properties, clock, DEFAULT_MAX_TRACKED_KEYS);
    }

    RequestRateLimiter(RateLimitProperties properties, Clock clock, int maxTrackedKeys) {
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
     * Counts one request. Empty means it may proceed; otherwise the time until the next token of the slowest refused
     * bucket, in whole seconds rounded up and never below one second.
     */
    public Optional<Duration> tryConsume(RateLimitedOperation operation, String ipKey, Optional<String> accountKey) {
        purgeIfDue();
        RateLimitProperties.Limit limit = properties.limit(operation);
        String ipBucketKey = operation + "|ip|" + (ipKey == null ? "" : ipKey);
        boolean hasAccount = limit.accountCapacity() != null && accountKey.isPresent();
        String accountBucketKey = hasAccount ? operation + "|account|" + accountKey.get() : null;

        int needed = (isNew(ipBucketKey) ? 1 : 0) + (hasAccount && isNew(accountBucketKey) ? 1 : 0);
        if (needed > 0 && buckets.size() + needed > maxTrackedKeys) {
            return Optional.of(roundUp(PURGE_INTERVAL));
        }

        int ipCapacity = Math.max(limit.ipCapacity(), properties.ipCapacityFloor());
        Bucket ipBucket = bucket(ipBucketKey, ipCapacity, limit.ipWindow());
        Bucket accountBucket =
                hasAccount ? bucket(accountBucketKey, limit.accountCapacity(), limit.accountWindow()) : null;

        ConsumptionProbe ip = ipBucket.tryConsumeAndReturnRemaining(1);
        ConsumptionProbe account = hasAccount ? accountBucket.tryConsumeAndReturnRemaining(1) : null;
        boolean ipRefused = !ip.isConsumed();
        boolean accountRefused = hasAccount && !account.isConsumed();
        if (!ipRefused && !accountRefused) {
            return Optional.empty();
        }
        // a refusal in one bucket must not spend the other
        if (!ipRefused) {
            ipBucket.addTokens(1);
        }
        if (hasAccount && !accountRefused) {
            accountBucket.addTokens(1);
        }
        long nanos = Math.max(
                ipRefused ? ip.getNanosToWaitForRefill() : 0, accountRefused ? account.getNanosToWaitForRefill() : 0);
        return Optional.of(roundUp(Duration.ofNanos(nanos)));
    }

    int trackedKeys() {
        return buckets.size();
    }

    private boolean isNew(String key) {
        return !buckets.containsKey(key);
    }

    private static Duration roundUp(Duration wait) {
        long seconds = (wait.toNanos() + 999_999_999L) / 1_000_000_000L;
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    private Bucket bucket(String key, int capacity, Duration window) {
        return buckets.computeIfAbsent(
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
        for (String key : buckets.keySet()) {
            buckets.computeIfPresent(
                    key, (k, tracked) -> tracked.bucket().getAvailableTokens() >= tracked.capacity() ? null : tracked);
        }
    }
}
