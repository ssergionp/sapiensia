package br.com.sergio.sapiensia.library;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The active Accesses of a Customer; an unpublished Product stays listed (ADR-0007). */
@Repository
class LibraryItems {

    private final JdbcClient jdbc;

    LibraryItems(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<LibraryItem> of(UUID customerId) {
        return jdbc.sql("select a.product_id, p.title, a.granted_at, c.version as cover_version"
                        + " from accesses a"
                        + " join products p on p.id = a.product_id"
                        + " left join product_covers c on c.product_id = a.product_id"
                        + " where a.customer_id = :customerId and a.cancelled_at is null and a.suspended_at is null"
                        + " order by a.granted_at desc, p.title")
                .param("customerId", customerId)
                .query((rs, row) -> new LibraryItem(
                        rs.getObject("product_id", UUID.class),
                        rs.getString("title"),
                        rs.getObject("granted_at", Timestamp.class).toInstant(),
                        rs.getString("cover_version")))
                .list();
    }
}
