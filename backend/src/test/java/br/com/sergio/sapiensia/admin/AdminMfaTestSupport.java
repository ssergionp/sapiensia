package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.security.SecretCipher;
import br.com.sergio.sapiensia.security.Totp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** MFA helpers for tests against the real server: a clock the test moves, and an enrolled TOTP secret. */
final class AdminMfaTestSupport {

    private AdminMfaTestSupport() {}

    /** A clock the test moves by hand; read by the server threads, so it is thread-safe. */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void advance(Duration duration) {
            now.updateAndGet(t -> t.plus(duration));
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
            return now.get();
        }
    }

    /** Replaces the application clock (limiter, pending MFA expiry, TOTP step) with one the test controls. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    /** Stores a new secret for the Administrator with MFA already active, as the enrollment would; returns it. */
    static byte[] enableMfa(JdbcTemplate jdbc, SecretCipher cipher, UUID administratorId) {
        byte[] secret = Totp.newSecret();
        jdbc.update(
                "update administrators set totp_secret = ?, totp_enabled_at = now(), totp_last_step = null"
                        + " where id = ?",
                cipher.encrypt(secret, "administrator-totp:" + administratorId),
                administratorId);
        return secret;
    }

    /** The 6-digit code of the step that contains {@code at}. */
    static String codeAt(byte[] secret, Instant at) {
        return Totp.code(secret, Totp.stepAt(at), 6);
    }

    /** A well-formed code of none of the three steps accepted at {@code at}. */
    static String wrongCodeAt(byte[] secret, Instant at) {
        long step = Totp.stepAt(at);
        return firstCodeNotIn(
                Totp.code(secret, step - 1, 6), Totp.code(secret, step, 6), Totp.code(secret, step + 1, 6));
    }

    /** The first 6-digit code, counting from 000000, that is not in {@code accepted}; repeated codes are fine. */
    static String firstCodeNotIn(String... accepted) {
        java.util.Set<String> rejected = new java.util.HashSet<>(java.util.Arrays.asList(accepted));
        for (int i = 0; ; i++) {
            String candidate = String.format(java.util.Locale.ROOT, "%06d", i);
            if (!rejected.contains(candidate)) {
                return candidate;
            }
        }
    }
}
