package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.security.Tokens;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Stores the set-password links of the Administrator. Only the SHA-256 hash of a token is persisted. */
@Component
class AdministratorPasswordTokens {

    private final JdbcClient jdbc;
    private final Duration ttl;

    /** The only place that reads {@code app.admin.password-link-ttl}: the e-mail states this same value. */
    AdministratorPasswordTokens(JdbcClient jdbc, @Value("${app.admin.password-link-ttl}") Duration ttl) {
        if (ttl.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalStateException("app.admin.password-link-ttl must be at least 1 minute");
        }
        this.jdbc = jdbc;
        this.ttl = ttl;
    }

    /** How long a new link stays valid. */
    Duration ttl() {
        return ttl;
    }

    /**
     * Replaces the unused links with the new one and records the send time, in one transaction. The Administrator row
     * is locked first. Called only after the e-mail was sent, so a failed send stores nothing. Does nothing if the
     * Administrator is gone or already has a password.
     */
    @Transactional
    void replaceWith(UUID administratorId, String tokenHash) {
        Optional<UUID> locked = jdbc.sql(
                        "select id from administrators where id = :id and password_hash is null for update")
                .param("id", administratorId)
                .query(UUID.class)
                .optional();
        if (locked.isEmpty()) {
            return;
        }
        jdbc.sql("delete from administrator_password_tokens where administrator_id = :id and used_at is null")
                .param("id", administratorId)
                .update();
        jdbc.sql("insert into administrator_password_tokens (administrator_id, token_hash, expires_at)"
                        + " values (:id, :hash, now() + make_interval(secs => :seconds))")
                .param("id", administratorId)
                .param("hash", tokenHash)
                .param("seconds", ttl.toSeconds())
                .update();
        jdbc.sql("update administrators set password_link_sent_at = now() where id = :id")
                .param("id", administratorId)
                .update();
    }

    /**
     * Uses the link and sets the password hash in one statement, so only one request wins. It also increments the
     * session version. Unknown, used and expired links, and a link of an Administrator who already has a password,
     * give the same empty result.
     *
     * @return the Administrator id, or empty if the link is not valid
     */
    Optional<UUID> consumeAndSetPassword(String token, String passwordHash) {
        return jdbc.sql("with used as (update administrator_password_tokens set used_at = now()"
                        + " where token_hash = :hash and used_at is null and expires_at > now()"
                        + " returning administrator_id)"
                        + " update administrators set password_hash = :passwordHash,"
                        + " session_version = session_version + 1 from used"
                        // a link never replaces a password that is already set, however it survived
                        + " where administrators.id = used.administrator_id and administrators.password_hash is null"
                        + " returning administrators.id")
                .param("hash", Tokens.hash(token))
                .param("passwordHash", passwordHash)
                .query(UUID.class)
                .optional();
    }
}
