package br.com.sergio.sapiensia.customer;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Stores and revokes password reset tokens. Only the SHA-256 hash of a token is persisted. */
@Component
class PasswordResetTokens {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final JdbcClient jdbc;
    private final Duration ttl;

    PasswordResetTokens(JdbcClient jdbc, @Value("${app.password-reset.ttl}") Duration ttl) {
        this.jdbc = jdbc;
        this.ttl = ttl;
    }

    /** Creates a token for the customer and returns it in plain text; only its hash is stored. */
    String issue(UUID customerId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("insert into password_reset_tokens (customer_id, token_hash, expires_at)"
                        + " values (:customerId, :hash, now() + make_interval(secs => :seconds))")
                .param("customerId", customerId)
                .param("hash", EmailConfirmationTokens.hash(token))
                .param("seconds", ttl.toSeconds())
                .update();
        return token;
    }

    /**
     * Uses the link and sets the new password hash in one statement, so only the request that marks the link as used
     * changes the password. The same statement increments the session version, which ends every session opened
     * before (SEC-006), so the password never changes without that. Unknown, used and expired links give the same
     * empty result.
     *
     * @return the customer id, or empty if the link is not valid
     */
    Optional<UUID> consumeAndSetPassword(String token, String newPasswordHash) {
        return jdbc.sql("with used as (update password_reset_tokens set used_at = now()"
                        + " where token_hash = :hash and used_at is null and expires_at > now()"
                        + " returning customer_id)"
                        + " update customers set password_hash = :passwordHash,"
                        + " session_version = session_version + 1 from used"
                        + " where customers.id = used.customer_id returning customers.id")
                .param("hash", EmailConfirmationTokens.hash(token))
                .param("passwordHash", newPasswordHash)
                .query(UUID.class)
                .optional();
    }

    /** Deletes the customer's links that were not used yet, so a new request leaves only the latest one valid. */
    void revokeUnused(UUID customerId) {
        jdbc.sql("delete from password_reset_tokens where customer_id = :customerId and used_at is null")
                .param("customerId", customerId)
                .update();
    }
}
