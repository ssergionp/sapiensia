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
    private static final LoginLimitProperties PROPERTIES =
            new LoginLimitProperties(5, Duration.ofMinutes(15), Duration.ofMinutes(15), 20, Duration.ofMinutes(15), "");

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(PROPERTIES, clock);
    private int ipSequence;

    /** An attempt from an IP that was never used before, so only the account limit is in play. */
    private LoginAttemptLimiter.Admission attempt(String email) {
        return attempt(limiter, email);
    }

    private LoginAttemptLimiter.Admission attempt(LoginAttemptLimiter target, String email) {
        ipSequence++;
        return target.admit(email, "198.18." + (ipSequence / 250) + "." + (ipSequence % 250 + 1));
    }

    private void attempts(String email, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(attempt(email).allowed()).isTrue();
        }
    }

    @Test
    @DisplayName("SEC-002: shouldAdmitFiveAttemptsAndRefuseTheSixthWithinWindow")
    void shouldAdmitFiveAttemptsAndRefuseTheSixthWithinWindow() {
        attempts(EMAIL, 5);

        assertThat(attempt(EMAIL).blockedFor()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("SEC-002: shouldUnblockAccountExactlyWhenLockEnds")
    void shouldUnblockAccountExactlyWhenLockEnds() {
        attempts(EMAIL, 5);

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(attempt(EMAIL).blockedFor()).isEqualTo(Duration.ofSeconds(1));

        clock.advance(Duration.ofSeconds(1));
        assertThat(attempt(EMAIL).allowed()).isTrue();
        // counting restarted: it takes five new attempts to lock again
        attempts(EMAIL, 3);
        assertThat(attempt(EMAIL).allowed()).isTrue();
        assertThat(attempt(EMAIL).allowed()).isFalse();
    }

    @Test
    void shouldNotExtendLockWhenAttemptsAreRefusedWhileLocked() {
        attempts(EMAIL, 5);
        clock.advance(Duration.ofMinutes(10));

        for (int i = 0; i < 3; i++) {
            assertThat(attempt(EMAIL).allowed()).isFalse();
        }

        assertThat(attempt(EMAIL).blockedFor()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("SEC-002: shouldIgnoreFailuresOlderThanWindow")
    void shouldIgnoreFailuresOlderThanWindow() {
        attempts(EMAIL, 4);
        clock.advance(Duration.ofMinutes(15));

        // the four old attempts no longer count: this is the first of a new window, so four more fit before the lock
        attempts(EMAIL, 5);
        assertThat(attempt(EMAIL).allowed()).isFalse();
    }

    @Test
    @DisplayName("SEC-002: shouldResetFailuresAfterSuccess")
    void shouldResetFailuresAfterSuccess() {
        attempts(EMAIL, 4);
        limiter.recordSuccess(EMAIL);

        attempts(EMAIL, 5);

        assertThat(attempt(EMAIL).allowed()).isFalse();
    }

    @Test
    @DisplayName("SEC-002: shouldTreatEmailCaseAndSpacesAsTheSameAccount")
    void shouldTreatEmailCaseAndSpacesAsTheSameAccount() {
        attempts("Ana@Example.com ", 3);
        attempts("ana@example.com", 2);

        assertThat(attempt(" ANA@example.com").allowed()).isFalse();
    }

    @Test
    @DisplayName("SEC-002: shouldFreeTheReservedSlotWhenTheAttemptIsReleased")
    void shouldFreeTheReservedSlotWhenTheAttemptIsReleased() {
        attempts(EMAIL, 4);
        LoginAttemptLimiter.Admission fifth = attempt(EMAIL);
        assertThat(fifth.allowed()).isTrue();

        limiter.release(EMAIL, fifth);

        // the released slot is free again, and the account is no longer locked
        assertThat(attempt(EMAIL).allowed()).isTrue();
        assertThat(attempt(EMAIL).allowed()).isFalse();
    }

    @Test
    void shouldIgnoreReleaseOfAnAttemptThatIsAlreadyGone() {
        LoginAttemptLimiter.Admission first = attempt(EMAIL);
        limiter.recordSuccess(EMAIL);
        clock.advance(Duration.ofSeconds(1));
        attempts(EMAIL, 2);

        limiter.release(EMAIL, first);

        // only the two later attempts are counted: three more fit before the lock
        attempts(EMAIL, 3);
        assertThat(attempt(EMAIL).allowed()).isFalse();
    }

    @Test
    @DisplayName("SEC-014: shouldLimitIpIndependentlyOfAccounts")
    void shouldLimitIpIndependentlyOfAccounts() {
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.admit("user" + i + "@example.com", "198.51.100.1")
                            .allowed())
                    .isTrue();
        }
        assertThat(limiter.admit("user20@example.com", "198.51.100.1").allowed())
                .isFalse();
        assertThat(limiter.admit("user0@example.com", "198.51.100.2").allowed()).isTrue();
    }

    @Test
    @DisplayName("SEC-014: shouldKeepIpWindowFixedAndRefuseTheTwentyFirstAttempt")
    void shouldKeepIpWindowFixedAndRefuseTheTwentyFirstAttempt() {
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.admit("a" + i + "@example.com", IP).allowed()).isTrue();
        }
        assertThat(limiter.admit("late@example.com", IP).blockedFor()).isEqualTo(Duration.ofMinutes(15));

        // no gradual refill: a minute later, and just before the window ends, it is still refused
        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.admit("late@example.com", IP).blockedFor()).isEqualTo(Duration.ofMinutes(14));
        clock.advance(Duration.ofMinutes(13).plusSeconds(59));
        assertThat(limiter.admit("late@example.com", IP).blockedFor()).isEqualTo(Duration.ofSeconds(1));

        // the window ends: exactly twenty more fit
        clock.advance(Duration.ofSeconds(1));
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.admit("b" + i + "@example.com", IP).allowed()).isTrue();
        }
        assertThat(limiter.admit("late@example.com", IP).allowed()).isFalse();
    }

    @Test
    @DisplayName("SEC-014: shouldLockAccountFromManyIpsWithoutExhaustingAnyIp")
    void shouldLockAccountFromManyIpsWithoutExhaustingAnyIp() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.admit(EMAIL, "192.0.2." + i).allowed()).isTrue();
        }

        assertThat(limiter.admit(EMAIL, "192.0.2.99").allowed()).isFalse();
        assertThat(limiter.admit("other@example.com", "192.0.2.0").allowed()).isTrue();
    }

    @Test
    @DisplayName("SEC-014: shouldSpendIpTokenEvenWhenAccountIsLocked")
    void shouldSpendIpTokenEvenWhenAccountIsLocked() {
        String ip = "198.51.100.9";
        attempts(EMAIL, 5);
        for (int i = 0; i < 20; i++) {
            assertThat(limiter.admit(EMAIL, ip).allowed()).isFalse();
        }

        // the 20 calls for the locked account drained the tokens of this IP, so an unlocked account is blocked too
        assertThat(limiter.admit("other@example.com", ip).allowed()).isFalse();
        assertThat(limiter.admit("other@example.com", "198.51.100.10").allowed())
                .isTrue();
    }

    @Test
    void shouldNotReserveASlotWhenTheIpIsBlocked() {
        for (int i = 0; i < 20; i++) {
            limiter.admit("x" + i + "@example.com", IP);
        }

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.admit(EMAIL, IP).allowed()).isFalse();
        }

        // the ten refused calls did not count as attempts of the account
        attempts(EMAIL, 5);
    }

    @Test
    void shouldReturnTheAccountWaitWhenItIsLargerThanTheIpWait() {
        for (int i = 0; i < 20; i++) {
            limiter.admit("x" + i + "@example.com", IP);
        }
        clock.advance(Duration.ofMinutes(10));
        attempts(EMAIL, 5);
        clock.advance(Duration.ofMinutes(4));

        // the IP window ends in 1 minute; the account lock, set 4 minutes ago, ends in 11
        assertThat(limiter.admit(EMAIL, IP).blockedFor()).isEqualTo(Duration.ofMinutes(11));
    }

    @Test
    void shouldReturnTheIpWaitWhenItIsLargerThanTheAccountWait() {
        attempts(EMAIL, 5);
        clock.advance(Duration.ofMinutes(14));
        for (int i = 0; i < 20; i++) {
            limiter.admit("x" + i + "@example.com", IP);
        }

        // the account lock ends in 1 minute; the IP window, opened just now, ends in 15
        assertThat(limiter.admit(EMAIL, IP).blockedFor()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void shouldNotThrowForNullEmailOrIp() {
        assertThat(limiter.admit(null, null).allowed()).isTrue();
        limiter.recordSuccess(null);
    }

    @Test
    @DisplayName("shouldDropExpiredEntries")
    void shouldDropExpiredEntries() {
        attempts(EMAIL, 2);
        assertThat(limiter.trackedAccounts()).isEqualTo(1);
        assertThat(limiter.trackedIps()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(15));
        limiter.recordSuccess("nobody@example.com");

        assertThat(limiter.trackedAccounts()).isZero();
        assertThat(limiter.trackedIps()).isZero();
    }

    @Test
    @DisplayName("SEC-002: shouldRefuseNewKeysWhenFullWithoutLoosenessForTrackedAccounts")
    void shouldRefuseNewKeysWhenFullWithoutLoosenessForTrackedAccounts() {
        LoginAttemptLimiter small = new LoginAttemptLimiter(PROPERTIES, clock, 3);
        for (String email : new String[] {EMAIL, "b@example.com", "c@example.com"}) {
            assertThat(small.admit(email, IP).allowed()).isTrue();
        }

        // flooding with new e-mails is refused and evicts nobody...
        for (int i = 0; i < 30; i++) {
            assertThat(small.admit("flood" + i + "@example.com", IP).allowed()).isFalse();
        }
        assertThat(small.trackedAccounts()).isEqualTo(3);
        // ...so the tracked account still locks on its fifth attempt, never later
        for (int i = 0; i < 4; i++) {
            assertThat(small.admit(EMAIL, IP).allowed()).isTrue();
        }
        assertThat(small.admit(EMAIL, IP).allowed()).isFalse();

        // the refusal is temporary: once the entries expire, new e-mails are admitted again
        clock.advance(Duration.ofMinutes(16));
        small.recordSuccess("nobody@example.com");
        assertThat(small.admit("flood0@example.com", IP).allowed()).isTrue();
    }

    @Test
    @DisplayName("SEC-002: shouldRefuseNewIpsWhenFull")
    void shouldRefuseNewIpsWhenFull() {
        LoginAttemptLimiter small = new LoginAttemptLimiter(PROPERTIES, clock, 2);
        assertThat(small.admit("a@example.com", "192.0.2.1").allowed()).isTrue();
        assertThat(small.admit("b@example.com", "192.0.2.2").allowed()).isTrue();

        assertThat(small.admit("c@example.com", "192.0.2.3").allowed()).isFalse();
        assertThat(small.admit("a@example.com", "192.0.2.1").allowed()).isTrue();
        assertThat(small.trackedIps()).isEqualTo(2);
    }

    @Test
    @DisplayName("SEC-002: shouldNeverPurgeAnAccountThatIsStillLocked")
    void shouldNeverPurgeAnAccountThatIsStillLocked() {
        attempts("old@example.com", 2);
        clock.advance(Duration.ofMinutes(10));
        attempts(EMAIL, 5);

        // the two old attempts are now out of the window and purgeable; the lock set 10 minutes later is not
        clock.advance(Duration.ofMinutes(6));
        limiter.recordSuccess("nobody@example.com");

        assertThat(limiter.trackedAccounts()).isEqualTo(1);
        assertThat(attempt(EMAIL).blockedFor()).isEqualTo(Duration.ofMinutes(9));
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
