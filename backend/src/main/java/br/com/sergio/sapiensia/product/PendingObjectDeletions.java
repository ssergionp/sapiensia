package br.com.sergio.sapiensia.product;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Access to {@code pending_object_deletions}: keys of storage objects to delete later (covers, replaced Product files,
 * possible orphans). The current cover and the current Product file are never candidates. Package-private.
 */
@Repository
class PendingObjectDeletions {

    static final int BATCH_SIZE = 500;

    private final JdbcClient jdbc;

    PendingObjectDeletions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void add(String key) {
        jdbc.sql("insert into pending_object_deletions (object_key) values (:key) on conflict do nothing")
                .param("key", key)
                .update();
    }

    void remove(String key) {
        jdbc.sql("delete from pending_object_deletions where object_key = :key")
                .param("key", key)
                .update();
    }

    /** Drops, in one statement, the old records whose key is a current cover or Product file: they are not orphans. */
    int removeStaleCurrentCovers(long minAgeSeconds) {
        return jdbc.sql("delete from pending_object_deletions p"
                        + " where p.created_at < now() - make_interval(secs => :age)"
                        + " and (exists (select 1 from product_covers c where c.object_key = p.object_key)"
                        + " or exists (select 1 from product_files f where f.object_key = p.object_key))")
                .param("age", (double) minAgeSeconds)
                .update();
    }

    /** At most {@value #BATCH_SIZE} keys older than {@code minAgeSeconds}, oldest first, none a current cover or file. */
    List<String> orphanCandidates(long minAgeSeconds) {
        return jdbc.sql("select p.object_key from pending_object_deletions p"
                        + " where p.created_at < now() - make_interval(secs => :age)"
                        + " and not exists (select 1 from product_covers c where c.object_key = p.object_key)"
                        + " and not exists (select 1 from product_files f where f.object_key = p.object_key)"
                        + " order by p.created_at limit " + BATCH_SIZE)
                .param("age", (double) minAgeSeconds)
                .query(String.class)
                .list();
    }
}
