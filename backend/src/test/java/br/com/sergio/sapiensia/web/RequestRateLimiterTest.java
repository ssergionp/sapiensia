package br.com.sergio.sapiensia.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RequestRateLimiterTest {

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final RateLimitedOperation WITH_ACCOUNT = RateLimitedOperation.SIGN_UP;
    private static final RateLimitedOperation IP_ONLY = RateLimitedOperation.COVER;

    private final MutableClock clock = new MutableClock();

    /** Every operation: 2 per account and 5 per IP in 10 minutes, except IP_ONLY (3 per IP, no account limit). */
    private static RateLimitProperties properties(int floor) {
        RateLimitProperties.Limit both = new RateLimitProperties.Limit(2, TEN_MINUTES, 5, TEN_MINUTES);
        RateLimitProperties.Limit ipOnly = new RateLimitProperties.Limit(null, null, 3, TEN_MINUTES);
        return new RateLimitProperties(floor, both, both, both, ipOnly, both, both, ipOnly, ipOnly, ipOnly);
    }

    private RequestRateLimiter limiter(int floor) {
        return new RequestRateLimiter(properties(floor), clock);
    }

    private RequestRateLimiter limiterWithLog() {
        return new RequestRateLimiter(properties(0), clock, 3, new RefusalLogThrottle(Duration.ofMinutes(1), clock));
    }

    private static void refuse(RequestRateLimiter limiter, RateLimitedOperation operation, String ip) {
        assertThatThrownBy(() -> limiter.require(operation, ip, Optional.empty()))
                .isInstanceOf(RateLimitedException.class);
    }

    private static long lines(CapturedOutput output, String text) {
        return output.getAll().lines().filter(l -> l.contains(text)).count();
    }

    @Test
    @DisplayName("SEC-014: shouldLogOneRefusalLinePerOperationPerMinute")
    void shouldLogOneRefusalLinePerOperationPerMinute(CapturedOutput output) {
        RequestRateLimiter limiter = limiterWithLog();
        for (int i = 0; i < 3; i++) {
            limiter.require(IP_ONLY, "192.0.2.1", Optional.empty());
        }
        for (int i = 0; i < 50; i++) {
            refuse(limiter, IP_ONLY, "192.0.2.1");
        }
        assertThat(lines(output, "Request limit reached for COVER")).isEqualTo(1);
        assertThat(output.getAll()).contains("Request limit reached for COVER: suppressed=0");

        // another operation in the same minute has its own line
        for (int i = 0; i < 3; i++) {
            limiter.require(RateLimitedOperation.PASSWORD_RESET_CONFIRM, "192.0.2.1", Optional.empty());
        }
        refuse(limiter, RateLimitedOperation.PASSWORD_RESET_CONFIRM, "192.0.2.1");
        assertThat(lines(output, "Request limit reached for PASSWORD_RESET_CONFIRM"))
                .isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        refuse(limiter, IP_ONLY, "192.0.2.1");
        assertThat(output.getAll()).contains("Request limit reached for COVER: suppressed=49");
        assertThat(output.getAll()).doesNotContain("192.0.2.");
    }

    @Test
    @DisplayName("SEC-014: shouldLogAFullTableApartFromAnOrdinaryRefusal")
    void shouldLogAFullTableApartFromAnOrdinaryRefusal(CapturedOutput output) {
        RequestRateLimiter limiter = limiterWithLog();
        for (int i = 1; i <= 3; i++) {
            limiter.require(IP_ONLY, "192.0.2." + i, Optional.empty());
        }
        for (int i = 0; i < 20; i++) {
            refuse(limiter, IP_ONLY, "198.51.100." + i);
        }
        assertThat(lines(output, "Request limit table full for COVER")).isEqualTo(1);
        assertThat(output.getAll())
                .doesNotContain("Request limit reached for COVER")
                .doesNotContain("198.51.100.");
    }

    private static Optional<String> account(String email) {
        return Optional.of(RequestRateLimiter.emailKey(email));
    }

    @Test
    @DisplayName("SEC-014: o N+1º pedido da mesma conta é recusado e o de outra conta passa")
    void shouldRefuseTheRequestAfterTheAccountCapacityAndLetAnotherAccountPass() {
        RequestRateLimiter limiter = limiter(0);
        for (int i = 0; i < 2; i++) {
            assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2." + i, account("ana@example.com")))
                    .isEmpty();
        }

        assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2.9", account("ana@example.com")))
                .isPresent();
        assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2.9", account("bia@example.com")))
                .isEmpty();
    }

    @Test
    @DisplayName("SEC-014: o N+1º pedido do mesmo IP é recusado mesmo com contas diferentes")
    void shouldRefuseTheRequestAfterTheIpCapacityEvenWithDifferentAccounts() {
        RequestRateLimiter limiter = limiter(0);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("u" + i + "@example.com")))
                    .isEmpty();
        }

        assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("new@example.com")))
                .isPresent();
        assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2.2", account("new@example.com")))
                .isEmpty();
    }

    @Test
    @DisplayName("SEC-014: shouldShareTheIpLimitBetweenIpv6AddressesOfTheSame64")
    void shouldShareTheIpLimitBetweenIpv6AddressesOfTheSame64() {
        RequestRateLimiter limiter = limiter(0);
        for (int i = 1; i <= 3; i++) {
            assertThat(limiter.tryConsume(IP_ONLY, "2001:db8:1:2::" + i, Optional.empty()))
                    .isEmpty();
        }
        assertThat(limiter.tryConsume(IP_ONLY, "2001:db8:1:2:aaaa:bbbb:cccc:dddd", Optional.empty()))
                .isPresent();
        assertThat(limiter.tryConsume(IP_ONLY, "2001:db8:1:3::1", Optional.empty()))
                .isEmpty();
        assertThat(limiter.trackedKeys()).isEqualTo(2);
    }

    @Test
    @DisplayName("SEC-014: shouldCountIpv6WithEmbeddedIpv4AsTheIpv4")
    void shouldCountIpv6WithEmbeddedIpv4AsTheIpv4() {
        RequestRateLimiter limiter = limiter(0);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume(IP_ONLY, "203.0.113.7", Optional.empty()))
                    .isEmpty();
        }
        assertThat(limiter.tryConsume(IP_ONLY, "::ffff:203.0.113.7", Optional.empty()))
                .isPresent();
        assertThat(limiter.tryConsume(IP_ONLY, "203.0.113.8", Optional.empty())).isEmpty();
    }

    @Test
    @DisplayName("SEC-014: pedido recusado pelo IP não cria chave de conta")
    void shouldNotCreateAnAccountKeyForARequestRefusedByTheIp() {
        RequestRateLimiter limiter = limiter(0);
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("u" + i + "@example.com"));
        }
        int tracked = limiter.trackedKeys();

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("new" + i + "@example.com")))
                    .isPresent();
        }

        assertThat(limiter.trackedKeys()).isEqualTo(tracked);
    }

    @Test
    @DisplayName("SEC-014: recusado num balde não gasta o outro")
    void shouldNotSpendTheOtherBucketWhenOneRefuses() {
        RequestRateLimiter limiter = limiter(0);
        // the account of ana is exhausted
        limiter.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("ana@example.com"));
        limiter.tryConsume(WITH_ACCOUNT, "192.0.2.2", account("ana@example.com"));
        // 10 refused requests from IP .7 must not spend its 5 tokens
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2.7", account("ana@example.com")))
                    .isPresent();
        }
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2.7", account("other" + i + "@example.com")))
                    .isEmpty();
        }
        assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2.7", account("other9@example.com")))
                .isPresent();

        // and the other way: a refused IP does not spend the account's tokens
        RequestRateLimiter second = limiter(0);
        for (int i = 0; i < 5; i++) {
            second.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("u" + i + "@example.com"));
        }
        for (int i = 0; i < 10; i++) {
            assertThat(second.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("carla@example.com")))
                    .isPresent();
        }
        assertThat(second.tryConsume(WITH_ACCOUNT, "192.0.2.50", account("carla@example.com")))
                .isEmpty();
        assertThat(second.tryConsume(WITH_ACCOUNT, "192.0.2.51", account("carla@example.com")))
                .isEmpty();
        assertThat(second.tryConsume(WITH_ACCOUNT, "192.0.2.52", account("carla@example.com")))
                .isPresent();
    }

    @Test
    @DisplayName("SEC-014: depois da janela o balde enche de novo")
    void shouldRefillWhenTheWindowEnds() {
        RequestRateLimiter limiter = limiter(0);
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty());
        }
        assertThat(limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty())).isPresent();

        clock.advance(TEN_MINUTES.minusSeconds(1));
        assertThat(limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty())).isPresent();
        clock.advance(Duration.ofSeconds(1));

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty()))
                    .isEmpty();
        }
        assertThat(limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty())).isPresent();
    }

    @Test
    @DisplayName("SEC-014: o tempo de espera é arredondado para cima e nunca zero")
    void shouldRoundTheWaitUpToWholeSecondsAndNeverReturnZero() {
        RequestRateLimiter limiter = limiter(0);
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty());
        }
        assertThat(limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty())).contains(TEN_MINUTES);

        clock.advance(Duration.ofMillis(1_500));
        // 598.5 s left: rounded up to 599
        assertThat(limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty())).contains(Duration.ofSeconds(599));

        // 1 ms before the window ends: never 0
        clock.advance(TEN_MINUTES.minusMillis(1_501));
        assertThat(limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty())).contains(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("SEC-014: o tempo de espera é o do maior dos dois baldes recusados")
    void shouldReturnTheLongerWaitOfTheTwoRefusedBuckets() {
        RateLimitProperties.Limit limit =
                new RateLimitProperties.Limit(1, Duration.ofMinutes(60), 1, Duration.ofMinutes(15));
        RateLimitProperties.Limit any = new RateLimitProperties.Limit(null, null, 1, TEN_MINUTES);
        RateLimitProperties properties = new RateLimitProperties(0, any, any, any, any, limit, any, any, any, any);
        RequestRateLimiter limiter = new RequestRateLimiter(properties, clock);
        limiter.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("ana@example.com"));

        assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("ana@example.com")))
                .contains(Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("SEC-014: a chave do e-mail é um hash e não contém o e-mail")
    void shouldBuildTheEmailKeyAsAHashOfTheNormalizedEmail() {
        String key = RequestRateLimiter.emailKey("  Ana@Example.com ");

        assertThat(key).matches("[0-9a-f]{64}").doesNotContain("ana").doesNotContain("example");
        assertThat(key).isEqualTo(RequestRateLimiter.emailKey("ana@example.com"));
        assertThat(key).isNotEqualTo(RequestRateLimiter.emailKey("bia@example.com"));
        // SHA-256 of "ana@example.com"
        assertThat(key).hasSize(64);
    }

    @Test
    @DisplayName("SEC-014: o piso de capacidade por IP eleva todo limite por IP e não mexe no por conta")
    void shouldRaiseEveryIpCapacityToTheFloorAndLeaveAccountLimitsAlone() {
        RequestRateLimiter limiter = limiter(50);
        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty()))
                    .isEmpty();
            assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2.2", account("u" + i + "@example.com")))
                    .isEmpty();
        }
        assertThat(limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty())).isPresent();
        assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2.2", account("last@example.com")))
                .isPresent();

        // the account limit (2) is untouched by the floor
        limiter.tryConsume(WITH_ACCOUNT, "192.0.2.3", account("ana@example.com"));
        limiter.tryConsume(WITH_ACCOUNT, "192.0.2.4", account("ana@example.com"));
        assertThat(limiter.tryConsume(WITH_ACCOUNT, "192.0.2.5", account("ana@example.com")))
                .isPresent();
    }

    @Test
    @DisplayName("SEC-014: operação sem limite por conta ignora a chave da conta")
    void shouldIgnoreTheAccountKeyOfAnOperationWithoutAccountLimit() {
        RequestRateLimiter limiter = limiter(0);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume(IP_ONLY, "192.0.2." + i, account("ana@example.com")))
                    .isEmpty();
        }
        // same account key from yet another IP: nothing was counted per account
        assertThat(limiter.tryConsume(IP_ONLY, "192.0.2.99", account("ana@example.com")))
                .isEmpty();
        assertThat(limiter.trackedKeys()).isEqualTo(4);
    }

    @Test
    @DisplayName("SEC-014: operações diferentes têm baldes separados")
    void shouldKeepOperationsIndependent() {
        RequestRateLimiter limiter = limiter(0);
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty());
        }

        assertThat(limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty())).isPresent();
        assertThat(limiter.tryConsume(RateLimitedOperation.EMAIL_CONFIRMATION, "192.0.2.1", Optional.empty()))
                .isEmpty();
    }

    @Test
    @DisplayName("SEC-014: shouldKeepOtherOperationsOpenWhenOneOperationIsFull")
    void shouldKeepOtherOperationsOpenWhenOneOperationIsFull() {
        RequestRateLimiter small = new RequestRateLimiter(properties(0), clock, 3);
        for (int i = 1; i <= 3; i++) {
            assertThat(small.tryConsume(IP_ONLY, "192.0.2." + i, Optional.empty()))
                    .isEmpty();
        }
        assertThat(small.tryConsume(IP_ONLY, "198.51.100.1", Optional.empty())).isPresent();

        assertThat(small.tryConsume(WITH_ACCOUNT, "198.51.100.1", account("ana@example.com")))
                .isEmpty();
        assertThat(small.tryConsume(RateLimitedOperation.ORDER_CREATE, "198.51.100.1", Optional.of("customer-1")))
                .isEmpty();
        assertThat(small.trackedKeys()).isEqualTo(7);
    }

    @Test
    @DisplayName("SEC-014: shouldGiveTheIpTokenBackWhenTheAccountKeyDoesNotFit")
    void shouldGiveTheIpTokenBackWhenTheAccountKeyDoesNotFit() {
        // WITH_ACCOUNT: 5 per IP. The table of the operation holds 2 keys: one IP and one account.
        RequestRateLimiter small = new RequestRateLimiter(properties(0), clock, 2);
        assertThat(small.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("ana@example.com")))
                .isEmpty();
        for (int i = 0; i < 10; i++) {
            assertThat(small.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("new-" + i + "@example.com")))
                    .isPresent();
        }
        // the 10 refusals spent nothing: the IP still has 4 of its 5 tokens, but the account allows only one more
        assertThat(small.tryConsume(WITH_ACCOUNT, "192.0.2.1", account("ana@example.com")))
                .isEmpty();
        assertThat(small.trackedKeys()).isEqualTo(2);
    }

    @Test
    @DisplayName("SEC-014: shouldRefuseNewKeysWhenFullAndNeverEvictATrackedOne")
    void shouldRefuseNewKeysWhenFullAndNeverEvictATrackedOne() {
        RequestRateLimiter small = new RequestRateLimiter(properties(0), clock, 3);
        for (int i = 1; i <= 3; i++) {
            assertThat(small.tryConsume(IP_ONLY, "192.0.2." + i, Optional.empty()))
                    .isEmpty();
        }

        for (int i = 0; i < 30; i++) {
            assertThat(small.tryConsume(IP_ONLY, "198.51.100." + i, Optional.empty()))
                    .isPresent();
        }
        assertThat(small.trackedKeys()).isEqualTo(3);
        // a tracked key keeps its count: 3 per window, two left of the first
        assertThat(small.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty())).isEmpty();
        assertThat(small.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty())).isEmpty();
        assertThat(small.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty())).isPresent();

        // refusing a new key is temporary: once entries expire, new keys are admitted again
        clock.advance(TEN_MINUTES.plusMinutes(1));
        assertThat(small.tryConsume(IP_ONLY, "198.51.100.1", Optional.empty())).isEmpty();
    }

    @Test
    @DisplayName("SEC-014: shouldDropIdleBucketsAfterTheirWindow")
    void shouldDropIdleBucketsAfterTheirWindow() {
        RequestRateLimiter limiter = limiter(0);
        limiter.tryConsume(IP_ONLY, "192.0.2.1", Optional.empty());
        limiter.tryConsume(WITH_ACCOUNT, "192.0.2.2", account("ana@example.com"));
        assertThat(limiter.trackedKeys()).isEqualTo(3);

        clock.advance(TEN_MINUTES);
        limiter.tryConsume(IP_ONLY, "192.0.2.3", Optional.empty());

        assertThat(limiter.trackedKeys()).isEqualTo(1);
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
