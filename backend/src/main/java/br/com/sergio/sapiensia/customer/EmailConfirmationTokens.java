package br.com.sergio.sapiensia.customer;

import br.com.sergio.sapiensia.security.Tokens;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Stores, looks up and deletes e-mail confirmation tokens. Only the SHA-256 hash of a token is persisted. */
@Component
class EmailConfirmationTokens {

    /** A token with a reserved attempt: its customer's password hash and the attempts reserved so far, this one included. */
    record Pending(UUID tokenId, String passwordHash, int attempts) {}

    private final JdbcClient jdbc;
    private final Duration ttl;

    EmailConfirmationTokens(JdbcClient jdbc, @Value("${app.email-confirmation.ttl}") Duration ttl) {
        this.jdbc = jdbc;
        this.ttl = ttl;
    }

    /** Creates a token for the customer and returns it in plain text; only its hash is stored. */
    String issue(UUID customerId) {
        String token = Tokens.newToken();
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

    /**
     * Reserves one password attempt on the link in a single statement, before the slow password check, so parallel
     * guesses cannot exceed the limit. Empty if the link is unknown, expired, used or already at the limit.
     */
    Optional<Pending> reserveAttempt(String token, int maxWrongPasswords) {
        return jdbc.sql("with reserved as (update email_confirmation_tokens set failed_attempts = failed_attempts + 1"
                        + " where token_hash = :hash and expires_at > now() and failed_attempts < :max"
                        + " returning id, customer_id, failed_attempts)"
                        + " select r.id, r.failed_attempts, c.password_hash from reserved r"
                        + " join customers c on c.id = r.customer_id")
                .param("hash", hash(token))
                .param("max", maxWrongPasswords)
                .query((rs, i) -> new Pending(
                        rs.getObject("id", UUID.class), rs.getString("password_hash"), rs.getInt("failed_attempts")))
                .optional();
    }

    /** Daily cleanup: deletes expired links, skipping rows another transaction holds locked. */
    int deleteExpired() {
        return jdbc.sql("delete from email_confirmation_tokens where id in ("
                        + "select id from email_confirmation_tokens where expires_at <= now() for update skip locked)")
                .update();
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
     * <p>The attempt limit is not checked here: {@link #reserveAttempt} already refused any link at the limit before
     * the password was checked, and the 5th attempt may still be the correct one.
     *
     * @return true if this call confirmed the customer
     */
    boolean consumeAndConfirm(UUID tokenId, String verifiedPasswordHash) {
        return jdbc.sql("with used as (delete from email_confirmation_tokens where id = :id returning customer_id)"
                                + " update customers set email_confirmed_at = coalesce(email_confirmed_at, now())"
                                + " where id in (select customer_id from used) and password_hash = :verifiedHash")
                        .param("id", tokenId)
                        .param("verifiedHash", verifiedPasswordHash)
                        .update()
                > 0;
    }

    /** Forwards to {@link Tokens#hash}, the one hash; kept so the customer code and its tests keep this name. */
    static String hash(String token) {
        return Tokens.hash(token);
    }
}
