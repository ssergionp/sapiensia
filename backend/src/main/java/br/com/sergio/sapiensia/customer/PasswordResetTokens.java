package br.com.sergio.sapiensia.customer;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
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

    /** Deletes the customer's links that were not used yet, so a new request leaves only the latest one valid. */
    void revokeUnused(UUID customerId) {
        jdbc.sql("delete from password_reset_tokens where customer_id = :customerId and used_at is null")
                .param("customerId", customerId)
                .update();
    }
}
