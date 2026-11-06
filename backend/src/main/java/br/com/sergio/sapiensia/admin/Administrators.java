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
