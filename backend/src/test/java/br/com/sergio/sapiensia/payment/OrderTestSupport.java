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
}
