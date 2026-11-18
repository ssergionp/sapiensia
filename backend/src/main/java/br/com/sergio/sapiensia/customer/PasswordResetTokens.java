package br.com.sergio.sapiensia.customer;

import br.com.sergio.sapiensia.security.Tokens;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Stores and revokes password reset tokens. Only the SHA-256 hash of a token is persisted. */
@Component
class PasswordResetTokens {

    private final JdbcClient jdbc;
    private final Duration ttl;

    PasswordResetTokens(JdbcClient jdbc, @Value("${app.password-reset.ttl}") Duration ttl) {
        this.jdbc = jdbc;
        this.ttl = ttl;
    }

    /** Creates a token for the customer and returns it in plain text; only its hash is stored. */
    String issue(UUID customerId) {
        String token = newToken();
        jdbc.sql("insert into password_reset_tokens (customer_id, token_hash, expires_at)"
                        + " values (:customerId, :hash, now() + make_interval(secs => :seconds))")
                .param("customerId", customerId)
                .param("hash", EmailConfirmationTokens.hash(token))
                .param("seconds", ttl.toSeconds())
                .update();
        return token;
    }

    /** Forwards to {@link Tokens#newToken}, the one generator; kept so the customer code and its tests keep this name. */
    static String newToken() {
        return Tokens.newToken();
    }

    /**
     * Uses the link and sets the new password hash in one conditional statement, so only the request that marks the
     * link as used changes the password. The same statement increments the session version, which ends every session
     * opened before (SEC-006), so the password never changes without that. The customer row is locked before that
     * statement, in the same order as {@link #reissue} and the login, so they never deadlock. A second statement in the
     * same transaction then forgets the account's known devices (ADR-0004): it reads the database after any login that
     * held the lock, so a new password makes every device new again, including one registered meanwhile. Unknown, used
     * and expired links give the same empty result and forget nothing.
     *
     * @return the customer id, or empty if the link is not valid
     */
    @Transactional
    Optional<UUID> consumeAndSetPassword(String token, String newPasswordHash) {
        String hash = EmailConfirmationTokens.hash(token);
        Optional<UUID> owner = jdbc.sql("select customer_id from password_reset_tokens where token_hash = :hash")
                .param("hash", hash)
                .query(UUID.class)
                .optional();
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        // the same lock as the login takes: waits for a login of this account that is registering a device
        jdbc.sql("select id from customers where id = :customerId for no key update")
                .param("customerId", owner.get())
                .query(UUID.class)
                .optional();
        Optional<UUID> changed = jdbc.sql("with used as (update password_reset_tokens set used_at = now()"
                        + " where token_hash = :hash and used_at is null and expires_at > now()"
                        + " returning customer_id)"
                        + " update customers set password_hash = :passwordHash,"
                        + " session_version = session_version + 1 from used"
                        + " where customers.id = used.customer_id returning customers.id")
                .param("hash", hash)
                .param("passwordHash", newPasswordHash)
                .query(UUID.class)
                .optional();
        changed.ifPresent(customerId -> jdbc.sql("delete from customer_devices where customer_id = :customerId")
                .param("customerId", customerId)
                .update());
        return changed;
    }

    /**
     * Replaces the customer's unused links with a new one and returns it in plain text. The customer row is locked
     * first, so parallel requests for the same account run one after the other and leave a single valid link. The
     * caller sends the e-mail after this returns, so the lock is never held during the send.
     */
    @Transactional
    String reissue(UUID customerId) {
        jdbc.sql("select id from customers where id = :customerId for update")
                .param("customerId", customerId)
                .query(UUID.class)
                .optional();
        revokeUnused(customerId);
        return issue(customerId);
    }

    /** Deletes the customer's links that were not used yet, so a new request leaves only the latest one valid. */
    void revokeUnused(UUID customerId) {
        jdbc.sql("delete from password_reset_tokens where customer_id = :customerId and used_at is null")
                .param("customerId", customerId)
                .update();
    }

    /** Daily cleanup: deletes used or expired links, skipping rows another transaction holds locked. */
    int deleteUsedOrExpired() {
        return jdbc.sql("delete from password_reset_tokens where id in ("
                        + "select id from password_reset_tokens"
                        + " where used_at is not null or expires_at <= now() for update skip locked)")
                .update();
    }
}
