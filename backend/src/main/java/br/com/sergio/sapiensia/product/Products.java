package br.com.sergio.sapiensia.product;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Access to the {@code products} table. Package-private: other features go through the service. */
@Repository
class Products {

    static final int PAGE_SIZE = 20;

    private static final String COLUMNS =
            "id, title, description, category, price_cents, status, version, created_at, updated_at";

    private static final RowMapper<ProductResponse> MAPPER = (rs, i) -> new ProductResponse(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("description"),
            ProductCategory.valueOf(rs.getString("category")),
            rs.getInt("price_cents"),
            ProductStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    Products(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a Product in DRAFT, version 0; the id and the dates come from the database. */
    ProductResponse insert(ProductRequest request) {
        return jdbc.sql("insert into products (title, description, category, price_cents)"
                        + " values (:title, :description, :category, :priceCents) returning " + COLUMNS)
                .param("title", request.title())
                .param("description", request.description())
                .param("category", request.category().name())
                .param("priceCents", request.priceCents())
                .query(MAPPER)
                .single();
    }

    Optional<ProductResponse> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from products where id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /**
     * Replaces title, description and category if the stored version is the one the client read, and bumps it. Returns
     * the rows changed: 0 means no such Product or a stale version (see {@link #exists}). Status, price and created_at
     * are never touched.
     */
    int update(UUID id, ProductUpdateRequest request) {
        return jdbc.sql("update products set title = :title, description = :description, category = :category,"
                        + " version = version + 1, updated_at = now() where id = :id and version = :version")
                .param("title", request.title())
                .param("description", request.description())
                .param("category", request.category().name())
                .param("id", id)
                .param("version", request.version())
                .update();
    }

    /** Same lock as {@link #update}, for the price only. */
    int updatePrice(UUID id, ProductPriceRequest request) {
        return jdbc.sql("update products set price_cents = :priceCents, version = version + 1, updated_at = now()"
                        + " where id = :id and version = :version")
                .param("priceCents", request.priceCents())
                .param("id", id)
                .param("version", request.version())
                .update();
    }

    /** Tells a missing Product (404) from a stale version (409) after an update changed no row. */
    boolean exists(UUID id) {
        return jdbc.sql("select count(*) from products where id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** Newest first; the id breaks ties so pages never repeat or skip a row. The offset is a long: no int overflow. */
    List<ProductResponse> list(int page) {
        return jdbc.sql("select " + COLUMNS + " from products order by created_at desc, id desc"
                        + " limit :limit offset :offset")
                .param("limit", PAGE_SIZE)
                .param("offset", page * (long) PAGE_SIZE)
                .query(MAPPER)
                .list();
    }

    long count() {
        return jdbc.sql("select count(*) from products").query(Long.class).single();
    }
}
