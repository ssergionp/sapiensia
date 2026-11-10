package br.com.sergio.sapiensia.product;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Access to the {@code product_files} table (one current file per Product). Package-private. */
@Repository
class ProductFiles {

    private final JdbcClient jdbc;

    ProductFiles(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the Product row until the transaction ends; false when there is no such Product. */
    boolean lockProduct(UUID productId) {
        return jdbc.sql("select id from products where id = :id for update")
                .param("id", productId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    Optional<String> currentKey(UUID productId) {
        return jdbc.sql("select object_key from product_files where product_id = :id")
                .param("id", productId)
                .query(String.class)
                .optional();
    }

    /** Points the Product at the new object, replacing the previous row if any. */
    void swap(UUID productId, String key, long sizeBytes, int pageCount) {
        jdbc.sql("insert into product_files (product_id, object_key, size_bytes, page_count)"
                        + " values (:id, :key, :size, :pages)"
                        + " on conflict (product_id) do update set object_key = excluded.object_key,"
                        + " size_bytes = excluded.size_bytes, page_count = excluded.page_count, uploaded_at = now()")
                .param("id", productId)
                .param("key", key)
                .param("size", sizeBytes)
                .param("pages", pageCount)
                .update();
    }
}
