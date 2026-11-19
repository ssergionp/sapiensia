package br.com.sergio.sapiensia.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Samples the warning of a refusal that an anonymous client can repeat in a loop: one line per key per interval, with
 * the count of the lines that were held back. Keys must be fixed strings chosen by the code (an operation, a reason),
 * never data chosen by the client, so the table stays small. Only the log line goes through it: the refusal itself is
 * never sampled.
 *
 * <p>The count of held lines is reported only by the next line that passes for the same key: after a burst ends, the
 * count appears on the next refusal of that key, however much later that is (and never, if none comes).
 */
@Component
public class RefusalLogThrottle {

    /** The instant of the last line that passed and how many were held back since. */
    private record Entry(Instant lastPassed, long held) {}

    private final Duration interval;
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    RefusalLogThrottle(RefusalLogProperties properties, Clock clock) {
        this(properties.interval(), clock);
    }

    public RefusalLogThrottle(Duration interval, Clock clock) {
        this.interval = interval;
        this.clock = clock;
    }

    /**
     * Empty means do not write the line; a value means write it, and the value is how many lines of this key were held
     * back since the last one that passed. The first line of each key passes with 0.
     */
    public OptionalLong pass(String key) {
        Instant now = clock.instant();
        AtomicLong held = new AtomicLong(-1);
        entries.compute(key, (k, entry) -> {
            if (entry == null
                    || interval.isZero()
                    || !now.isBefore(entry.lastPassed().plus(interval))) {
                held.set(entry == null ? 0 : entry.held());
                return new Entry(now, 0);
            }
            return new Entry(entry.lastPassed(), entry.held() + 1);
        });
        return held.get() < 0 ? OptionalLong.empty() : OptionalLong.of(held.get());
    }
}
