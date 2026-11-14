package br.com.sergio.sapiensia.payment;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Creates the rows an Order needs (a confirmed Customer and a published Product) straight in the database. */
final class OrderTestSupport {

    private OrderTestSupport() {}

    static UUID insertCustomer(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, 'x', now()) returning id",
                UUID.class,
                "cliente-" + UUID.randomUUID() + "@example.com");
    }

    static UUID insertPublishedProduct(JdbcTemplate jdbc, String title, int priceCents) {
        return jdbc.queryForObject(
                "insert into products (title, description, category, price_cents, status, first_published_at)"
                        + " values (?, 'd', 'LAW', ?, 'PUBLISHED', now()) returning id",
                UUID.class,
                title,
                priceCents);
    }

    /**
     * Deletes the Orders (and their Accesses and provider link) of one Customer and of the given Products, and
     * nothing else. Never delete the whole table: other tests of the same context leave Orders that
     * payment_event_results rows point to, and that table is insert-only (SEC-028), so such an Order can never be
     * deleted.
     */
    static void deleteOrdersOf(JdbcTemplate jdbc, UUID customerId, UUID... productIds) {
        deleteOrdersOfProducts(jdbc, productIds);
        jdbc.update("delete from accesses where customer_id = ?", customerId);
        jdbc.update("delete from orders where customer_id = ?", customerId);
        jdbc.update("delete from payment_customers where customer_id = ?", customerId);
    }

    /** Same, for the Orders of the given Products only (and the provider links of the Customers who bought them). */
    static void deleteOrdersOfProducts(JdbcTemplate jdbc, UUID... productIds) {
        for (UUID productId : productIds) {
            jdbc.update(
                    "delete from payment_customers where customer_id in"
                            + " (select customer_id from orders where product_id = ?)",
                    productId);
            jdbc.update("delete from accesses where product_id = ?", productId);
            jdbc.update("delete from orders where product_id = ?", productId);
        }
    }

    static long countOrdersOf(JdbcTemplate jdbc, UUID customerId) {
        return jdbc.queryForObject("select count(*) from orders where customer_id = ?", Long.class, customerId);
    }
}
