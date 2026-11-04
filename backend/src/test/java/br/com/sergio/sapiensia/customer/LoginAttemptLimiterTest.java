package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    private static final String EMAIL = "ana@example.com";
    private static final String IP = "203.0.113.1";

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(
            new LoginLimitProperties(5, Duration.ofMinutes(15), Duration.ofMinutes(15), 20, Duration.ofMinutes(15), ""),
            clock);

    private void fail(String email, int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(email);
        }
    }

    @Test
    @DisplayName("SEC-002: shouldBlockAccountOnFifthFailureWithinWindow")
    void shouldBlockAccountOnFifthFailureWithinWindow() {
        fail(EMAIL, 4);
        assertThat(limiter.blockedFor(EMAIL, IP)).isEmpty();

        limiter.recordFailure(EMAIL);

        assertThat(limiter.blockedFor(EMAIL, IP)).contains(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("SEC-002: shouldUnblockAccountExactlyWhenLockEnds")
    void shouldUnblockAccountExactlyWhenLockEnds() {
        fail(EMAIL, 5);

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(limiter.blockedFor(EMAIL, IP)).contains(Duration.ofSeconds(1));

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.blockedFor(EMAIL, IP)).isEmpty();

        limiter.recordFailure(EMAIL);
        assertThat(limiter.blockedFor(EMAIL, IP)).isEmpty();
    }

    @Test
    void shouldNotExtendLockWhenFailureIsRecordedWhileLocked() {
        fail(EMAIL, 5);
        clock.advance(Duration.ofMinutes(10));

        fail(EMAIL, 3);

        assertThat(limiter.blockedFor(EMAIL, IP)).contains(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("SEC-002: shouldIgnoreFailuresOlderThanWindow")
    void shouldIgnoreFailuresOlderThanWindow() {
        fail(EMAIL, 4);
        clock.advance(Duration.ofMinutes(15));

        limiter.recordFailure(EMAIL);

        assertThat(limiter.blockedFor(EMAIL, IP)).isEmpty();
    }

    @Test
    @DisplayName("SEC-002: shouldResetFailuresAfterSuccess")
    void shouldResetFailuresAfterSuccess() {
        fail(EMAIL, 4);
        limiter.recordSuccess(EMAIL);

        fail(EMAIL, 4);

        assertThat(limiter.blockedFor(EMAIL, IP)).isEmpty();
    }

    @Test
    @DisplayName("SEC-002: shouldTreatEmailCaseAndSpacesAsTheSameAccount")
    void shouldTreatEmailCaseAndSpacesAsTheSameAccount() {
        fail("Ana@Example.com ", 3);
        fail("ana@example.com", 2);

        assertThat(limiter.blockedFor(" ANA@example.com", IP)).isPresent();
    }

    @Test
    @DisplayName("SEC-014: shouldLimitIpIndependentlyOfAccounts")
    void shouldLimitIpIndependentlyOfAccounts() {
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.blockedFor("user" + i + "@example.com", "198.51.100.1"))
                    .isEmpty();
        }
        assertThat(limiter.blockedFor("user20@example.com", "198.51.100.1")).isPresent();
        assertThat(limiter.blockedFor("user0@example.com", "198.51.100.2")).isEmpty();
    }

    @Test
    @DisplayName("SEC-014: shouldLockAccountFromManyIpsWithoutExhaustingAnyIp")
    void shouldLockAccountFromManyIpsWithoutExhaustingAnyIp() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.blockedFor(EMAIL, "192.0.2." + i)).isEmpty();
            limiter.recordFailure(EMAIL);
        }

        assertThat(limiter.blockedFor(EMAIL, "192.0.2.99")).isPresent();
        assertThat(limiter.blockedFor("other@example.com", "192.0.2.0")).isEmpty();
    }

    @Test
    @DisplayName("SEC-014: shouldAllowIpAgainAfterWindow")
    void shouldAllowIpAgainAfterWindow() {
        for (int i = 0; i < 20; i++) {
            limiter.blockedFor("a@example.com", IP);
        }
        assertThat(limiter.blockedFor("a@example.com", IP)).isPresent();

        clock.advance(Duration.ofMinutes(15));

        for (int i = 0; i < 20; i++) {
            assertThat(limiter.blockedFor("a@example.com", IP)).isEmpty();
        }
        assertThat(limiter.blockedFor("a@example.com", IP)).isPresent();
    }

    @Test
    void shouldReturnLargerWaitWhenIpAndAccountAreBothBlocked() {
        for (int i = 0; i < 21; i++) {
            limiter.blockedFor("x@example.com", IP);
        }
        fail(EMAIL, 5);
        clock.advance(Duration.ofMinutes(14));
        for (int i = 0; i < 20; i++) {
            limiter.blockedFor("y@example.com", IP);
        }

        // account lock has 1 minute left; the IP bucket was just drained and needs well under that for one token
        assertThat(limiter.blockedFor(EMAIL, IP)).contains(Duration.ofMinutes(1));
    }

    @Test
    void shouldNotThrowForNullEmailOrIp() {
        assertThat(limiter.blockedFor(null, null)).isEmpty();
        limiter.recordFailure(null);
        limiter.recordSuccess(null);
    }

    @Test
    @DisplayName("shouldDropExpiredEntries")
    void shouldDropExpiredEntries() {
        fail(EMAIL, 2);
        limiter.blockedFor(EMAIL, IP);
        assertThat(limiter.trackedKeys()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(15));
        limiter.recordSuccess("nobody@example.com");

        assertThat(limiter.trackedKeys()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

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
