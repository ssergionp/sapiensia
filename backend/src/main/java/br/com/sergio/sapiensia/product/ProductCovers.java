package br.com.sergio.sapiensia.product;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Access to the {@code product_covers} table (one current cover per Product). Package-private. */
@Repository
class ProductCovers {

    private final JdbcClient jdbc;

    ProductCovers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> currentKey(UUID productId) {
        return jdbc.sql("select object_key from product_covers where product_id = :id")
                .param("id", productId)
                .query(String.class)
                .optional();
    }

    void upsert(UUID productId, String key, String mediaType, int sizeBytes, int width, int height, String version) {
        jdbc.sql("insert into product_covers (product_id, object_key, media_type, size_bytes, width, height, version)"
                        + " values (:id, :key, :type, :size, :width, :height, :version)"
                        + " on conflict (product_id) do update set object_key = excluded.object_key,"
                        + " media_type = excluded.media_type, size_bytes = excluded.size_bytes,"
                        + " width = excluded.width, height = excluded.height, version = excluded.version,"
                        + " uploaded_at = now()")
                .param("id", productId)
                .param("key", key)
                .param("type", mediaType)
                .param("size", sizeBytes)
                .param("width", width)
                .param("height", height)
                .param("version", version)
                .update();
    }

    void delete(UUID productId) {
        jdbc.sql("delete from product_covers where product_id = :id")
                .param("id", productId)
                .update();
    }

    /** The cover of a PUBLISHED Product only; empty for a draft, an unpublished or a missing Product. */
    Optional<StoredCover> findPublished(UUID productId) {
        return jdbc.sql("select c.object_key, c.media_type, c.version from product_covers c"
                        + " join products p on p.id = c.product_id where c.product_id = :id and p.status = 'PUBLISHED'")
                .param("id", productId)
                .query((rs, i) -> new StoredCover(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional();
    }

    /** The cover of a Product the Customer has an active Access to, whatever the status of the Product. */
    Optional<StoredCover> findOwned(UUID customerId, UUID productId) {
        return jdbc.sql("select c.object_key, c.media_type, c.version from product_covers c"
                        + " join accesses a on a.product_id = c.product_id"
                        + " where a.customer_id = :customerId and a.product_id = :productId"
                        + " and a.cancelled_at is null")
                .param("customerId", customerId)
                .param("productId", productId)
                .query((rs, i) -> new StoredCover(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional();
    }

    Optional<StoredCover> find(UUID productId) {
        return jdbc.sql("select object_key, media_type, version from product_covers where product_id = :id")
                .param("id", productId)
                .query((rs, i) -> new StoredCover(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional();
    }
}
