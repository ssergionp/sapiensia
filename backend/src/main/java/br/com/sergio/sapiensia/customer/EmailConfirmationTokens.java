package br.com.sergio.sapiensia.customer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Stores, looks up and deletes e-mail confirmation tokens. Only the SHA-256 hash of a token is persisted. */
@Component
class EmailConfirmationTokens {

    /** A valid, unexpired token together with the password hash of its customer. */
    record Pending(UUID tokenId, String passwordHash) {}

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final JdbcClient jdbc;
    private final Duration ttl;

    EmailConfirmationTokens(JdbcClient jdbc, @Value("${app.email-confirmation.ttl}") Duration ttl) {
        this.jdbc = jdbc;
        this.ttl = ttl;
    }

    /** Creates a token for the customer and returns it in plain text; only its hash is stored. */
    String issue(UUID customerId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("insert into email_confirmation_tokens (customer_id, token_hash, expires_at)"
                        + " values (:customerId, :hash, now() + make_interval(secs => :seconds))")
                .param("customerId", customerId)
                .param("hash", hash(token))
                .param("seconds", ttl.toSeconds())
                .update();
        return token;
    }

    void revokeAll(UUID customerId) {
        jdbc.sql("delete from email_confirmation_tokens where customer_id = :customerId")
                .param("customerId", customerId)
                .update();
    }

    Optional<Pending> findValid(String token, int maxWrongPasswords) {
        return jdbc.sql("select t.id, c.password_hash from email_confirmation_tokens t"
                        + " join customers c on c.id = t.customer_id"
                        + " where t.token_hash = :hash and t.expires_at > now() and t.failed_attempts < :max")
                .param("hash", hash(token))
                .param("max", maxWrongPasswords)
                .query((rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getString("password_hash")))
                .optional();
    }

    /** Adds one wrong attempt atomically; returns the new count, or empty if the token is already gone. */
    Optional<Integer> recordWrongPassword(UUID tokenId) {
        return jdbc.sql("update email_confirmation_tokens set failed_attempts = failed_attempts + 1"
                        + " where id = :id returning failed_attempts")
                .param("id", tokenId)
                .query(Integer.class)
                .optional();
    }

    void delete(UUID tokenId) {
        jdbc.sql("delete from email_confirmation_tokens where id = :id")
                .param("id", tokenId)
                .update();
    }

    /**
     * Consumes the token and confirms its customer in one statement, only if the password hash is still the one the
     * caller verified. Of concurrent calls only one deletes the row.
     *
     * <p>If the password was replaced in the meantime (a newer registration), the token is still deleted but nothing is
     * confirmed: the link becomes useless instead of confirming the account with a password the caller never checked.
     *
     * @return true if this call confirmed the customer
     */
    boolean consumeAndConfirm(UUID tokenId, String verifiedPasswordHash, int maxWrongPasswords) {
        return jdbc.sql(
                                "with used as (delete from email_confirmation_tokens where id = :id and failed_attempts < :max returning customer_id)"
                                        + " update customers set email_confirmed_at = coalesce(email_confirmed_at, now())"
                                        + " where id in (select customer_id from used) and password_hash = :verifiedHash")
                        .param("id", tokenId)
                        .param("max", maxWrongPasswords)
                        .param("verifiedHash", verifiedPasswordHash)
                        .update()
                > 0;
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
