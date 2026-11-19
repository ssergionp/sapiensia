package br.com.sergio.sapiensia.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefusalLogThrottleTest {

    private final MutableClock clock = new MutableClock();

    @Test
    @DisplayName("SEC-014: shouldPassTheFirstLineAndHoldTheRestOfTheInterval")
    void shouldPassTheFirstLineAndHoldTheRestOfTheInterval() {
        RefusalLogThrottle throttle = new RefusalLogThrottle(Duration.ofMinutes(1), clock);
        assertThat(throttle.pass("a")).hasValue(0);
        for (int i = 0; i < 99; i++) {
            assertThat(throttle.pass("a")).isEmpty();
        }
        clock.advance(Duration.ofSeconds(59));
        assertThat(throttle.pass("a")).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        assertThat(throttle.pass("a")).hasValue(100);
        assertThat(throttle.pass("a")).isEmpty();
    }

    @Test
    @DisplayName("SEC-014: shouldCountEachKeyByItself")
    void shouldCountEachKeyByItself() {
        RefusalLogThrottle throttle = new RefusalLogThrottle(Duration.ofMinutes(1), clock);
        assertThat(throttle.pass("a")).hasValue(0);
        assertThat(throttle.pass("b")).hasValue(0);
        assertThat(throttle.pass("a")).isEmpty();
    }

    @Test
    @DisplayName("SEC-014: shouldNeverHoldWhenTheIntervalIsZero")
    void shouldNeverHoldWhenTheIntervalIsZero() {
        RefusalLogThrottle throttle = new RefusalLogThrottle(Duration.ZERO, clock);
        for (int i = 0; i < 5; i++) {
            assertThat(throttle.pass("a")).hasValue(0);
        }
    }

    @Test
    @DisplayName("SEC-014: shouldNotGoSilentWhenTheClockGoesBack")
    void shouldNotGoSilentWhenTheClockGoesBack() {
        RefusalLogThrottle throttle = new RefusalLogThrottle(Duration.ofMinutes(1), clock);
        assertThat(throttle.pass("a")).hasValue(0);
        assertThat(throttle.pass("a")).isEmpty();
        // the clock steps back 10 minutes: the next line passes and reports the one that was held
        clock.advance(Duration.ofMinutes(-10));
        assertThat(throttle.pass("a")).hasValue(1);
        // from there the interval counts again from the new instant
        assertThat(throttle.pass("a")).isEmpty();
        clock.advance(Duration.ofMinutes(1));
        assertThat(throttle.pass("a")).hasValue(1);
    }

    @Test
    @DisplayName("SEC-014: shouldLoseNoCountUnderConcurrency")
    void shouldLoseNoCountUnderConcurrency() throws Exception {
        // 8 threads x 1000 calls on one key inside one interval: exactly one passes, then the next interval reports
        // 7999
        RefusalLogThrottle throttle = new RefusalLogThrottle(Duration.ofMinutes(1), clock);
        AtomicInteger passed = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int t = 0; t < 8; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        if (throttle.pass("a").isPresent()) {
                            passed.incrementAndGet();
                        }
                    }
                });
            }
        }
        assertThat(passed).hasValue(1);
        clock.advance(Duration.ofMinutes(1));
        assertThat(throttle.pass("a")).hasValue(7999);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
