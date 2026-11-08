package br.com.sergio.sapiensia.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Access to the {@code administrator_recovery_codes} table: hashes only, never the codes. */
@Repository
class AdministratorRecoveryCodes {

    private final JdbcClient jdbc;

    AdministratorRecoveryCodes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The unused codes of one Administrator (at most 10): a code of another account never matches. */
    List<RecoveryCodes.Unused> findUnused(UUID administratorId) {
        return jdbc.sql("select id, code_hash from administrator_recovery_codes"
                        + " where administrator_id = :administratorId and used_at is null")
                .param("administratorId", administratorId)
                .query((rs, i) -> new RecoveryCodes.Unused(rs.getObject("id", UUID.class), rs.getString("code_hash")))
                .list();
    }

    /** Deletes every code of the account and stores the new hashes; call it in the transaction of the action. */
    void replaceAll(UUID administratorId, List<String> hashes) {
        jdbc.sql("delete from administrator_recovery_codes where administrator_id = :administratorId")
                .param("administratorId", administratorId)
                .update();
        for (String hash : hashes) {
            jdbc.sql("insert into administrator_recovery_codes (administrator_id, code_hash)"
                            + " values (:administratorId, :hash)")
                    .param("administratorId", administratorId)
                    .param("hash", hash)
                    .update();
        }
    }

    /**
     * Marks the code used, only if it still is unused: one conditional UPDATE, so of two requests with the same code
     * exactly one gets 1 row. Returns the rows changed (1 consumed, 0 refused).
     */
    int consume(UUID id) {
        return jdbc.sql("update administrator_recovery_codes set used_at = now() where id = :id and used_at is null")
                .param("id", id)
                .update();
    }
}
