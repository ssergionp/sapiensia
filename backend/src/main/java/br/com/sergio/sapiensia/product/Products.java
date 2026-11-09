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
