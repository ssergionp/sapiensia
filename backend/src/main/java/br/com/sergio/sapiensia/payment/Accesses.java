package br.com.sergio.sapiensia.payment;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The Accesses: the right of a Customer to download a Product (FR-024). */
@Repository
class Accesses {

    private final JdbcClient jdbc;

    Accesses(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True only when this call created the Access; false when the Customer already had it. */
    boolean grant(UUID customerId, UUID productId, UUID orderId, Instant now) {
        return jdbc.sql("insert into accesses (customer_id, product_id, order_id, granted_at)"
                                + " values (:customerId, :productId, :orderId, :now)"
                                + " on conflict (customer_id, product_id) do nothing")
                        .param("customerId", customerId)
                        .param("productId", productId)
                        .param("orderId", orderId)
                        .param("now", java.sql.Timestamp.from(now))
                        .update()
                == 1;
    }

    boolean exists(UUID customerId, UUID productId) {
        return jdbc.sql("select exists (select 1 from accesses where customer_id = :customerId"
                        + " and product_id = :productId)")
                .param("customerId", customerId)
                .param("productId", productId)
                .query(Boolean.class)
                .single();
    }
}
