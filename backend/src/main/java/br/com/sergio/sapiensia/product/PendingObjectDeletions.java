package br.com.sergio.sapiensia.product;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Access to {@code pending_object_deletions}: keys that may be orphan objects in the storage. Package-private. */
@Repository
class PendingObjectDeletions {

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

    /** Keys registered more than {@code minAgeSeconds} ago, oldest first. */
    List<String> olderThan(long minAgeSeconds) {
        return jdbc.sql("select object_key from pending_object_deletions"
                        + " where created_at < now() - make_interval(secs => :age) order by created_at")
                .param("age", (double) minAgeSeconds)
                .query(String.class)
                .list();
    }

    boolean isCurrentCover(String key) {
        return jdbc.sql("select count(*) from product_covers where object_key = :key")
                        .param("key", key)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
