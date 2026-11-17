package br.com.sergio.sapiensia.admin;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Access to the {@code administrators} table. Package-private: other features use {@link AdministratorSessions}. */
@Repository
class Administrators {

    /** The single Administrator row, as the bootstrap needs it. */
    record Pending(UUID id, String email) {}

    record Row(UUID id, String email, boolean passwordSet, boolean linkSentRecently) {}

    private final JdbcClient jdbc;

    Administrators(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Serializes bootstraps (several instances or starts) until the current transaction ends. */
    void lockBootstrap() {
        jdbc.sql("select pg_advisory_xact_lock(hashtext('administrator-bootstrap'))")
                .query()
                .listOfRows();
    }

    Optional<Row> findOnly(Duration minInterval) {
        return jdbc.sql("select id, email, password_hash is not null as password_set,"
                        + " coalesce(password_link_sent_at > now() - make_interval(secs => :seconds), false) as recent"
                        + " from administrators order by created_at limit 1")
                .param("seconds", minInterval.toSeconds())
                .query((rs, i) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getBoolean("password_set"),
                        rs.getBoolean("recent")))
                .optional();
    }

    /** Inserts the Administrator only if the table is empty. */
    Optional<UUID> insertIfEmpty(String email) {
        return jdbc.sql("insert into administrators (email) select :email"
                        + " where not exists (select 1 from administrators) returning id")
                .param("email", email)
                .query(UUID.class)
                .optional();
    }

    /** The Administrator that still has no password (there is only one). */
    Optional<Pending> findWithoutPassword() {
        return jdbc.sql("select id, email from administrators where password_hash is null"
                        + " order by created_at limit 1")
                .query((rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getString("email")))
                .optional();
    }

    record Login(UUID id, String email, String passwordHash, int sessionVersion) {

        // never the e-mail or the hash
        @Override
        public String toString() {
            return "Login[id=" + id + "]";
        }
    }

    /** The account for the login; {@code email} is already normalized. */
    Optional<Login> findForLogin(String email) {
        return jdbc.sql("select id, email, password_hash, session_version from administrators where email = :email")
                .param("email", email)
                .query((rs, i) -> new Login(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getInt("session_version")))
                .optional();
    }

    /** The stored TOTP state of the account: the encrypted secret (null if none) and whether it is active. */
    record StoredTotp(String secretCipher, boolean enabled) {

        // never the cipher text
        @Override
        public String toString() {
            return "StoredTotp[enabled=" + enabled + "]";
        }
    }

    /** Empty when the row no longer exists. */
    Optional<StoredTotp> findTotp(UUID id) {
        return jdbc.sql("select totp_secret, totp_enabled_at is not null as enabled from administrators where id = :id")
                .param("id", id)
                .query((rs, i) -> new StoredTotp(rs.getString("totp_secret"), rs.getBoolean("enabled")))
                .optional();
    }

    /**
     * Records {@code step} as the last accepted one, only if MFA is active and the step is greater than the last one:
     * one conditional UPDATE, so of two requests with the same code the row lock lets exactly one through. Returns the
     * number of rows changed (1 accepted, 0 refused).
     */
    int acceptStep(UUID id, long step) {
        return jdbc.sql("update administrators set totp_last_step = :step where id = :id"
                        + " and totp_enabled_at is not null"
                        + " and (totp_last_step is null or totp_last_step < :step)")
                .param("step", step)
                .param("id", id)
                .update();
    }

    /**
     * Stores a new, not yet active secret (and forgets the last step) only while MFA is inactive: one conditional
     * UPDATE, so an active MFA is never overwritten whatever the callers do. Returns the rows changed (0 = active).
     */
    int startEnrollment(UUID id, String secretCipher) {
        return jdbc.sql("update administrators set totp_secret = :cipher, totp_last_step = null"
                        + " where id = :id and totp_enabled_at is null")
                .param("cipher", secretCipher)
                .param("id", id)
                .update();
    }

    /**
     * Activates MFA with the secret that was confirmed by a code of {@code step}, only if it is still inactive and the
     * stored secret is still that one. Of parallel confirmations the row lock lets exactly one through. Returns the
     * rows changed (1 activated, 0 refused).
     */
    int enable(UUID id, String secretCipher, long step) {
        return jdbc.sql("update administrators set totp_enabled_at = now(), totp_last_step = :step"
                        + " where id = :id and totp_enabled_at is null and totp_secret = :cipher")
                .param("step", step)
                .param("id", id)
                .param("cipher", secretCipher)
                .update();
    }

    /**
     * Swaps the active secret for {@code newCipher} and records {@code step} (of the new secret) as the last accepted
     * one, only while MFA is active and the stored secret is still {@code oldCipher}: a compare-and-set, so of parallel
     * confirmations the row lock lets exactly one through. Returns the rows changed (1 swapped, 0 refused).
     */
    int replaceTotp(UUID id, String oldCipher, String newCipher, long step) {
        return jdbc.sql("update administrators set totp_secret = :new, totp_last_step = :step where id = :id"
                        + " and totp_enabled_at is not null and totp_secret = :old")
                .param("new", newCipher)
                .param("step", step)
                .param("id", id)
                .param("old", oldCipher)
                .update();
    }

    /**
     * Ends every session of the account (SEC-006): the version goes up, and the row stays locked until the current
     * transaction ends. Returns the new version, empty when the row no longer exists.
     */
    OptionalInt incrementSessionVersion(UUID id) {
        return jdbc.sql("update administrators set session_version = session_version + 1 where id = :id"
                        + " returning session_version")
                .param("id", id)
                .query(Integer.class)
                .optional()
                .map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
    }

    /** The session version, for the filter that ends sessions opened before a password change. */
    OptionalInt findSessionVersion(UUID id) {
        return jdbc.sql("select session_version from administrators where id = :id")
                .param("id", id)
                .query(Integer.class)
                .optional()
                .map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
    }
}
