package br.com.sergio.sapiensia.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Shared setup of the Administrator's order list tests: Customers, Products and Orders inserted directly. */
final class AdminOrderFixtures {

    static final String ORDERS = "/api/admin/orders";
    static final String CUSTOMER_PASSWORD = "senhalongaok"; // gitleaks:allow

    private AdminOrderFixtures() {}

    static UUID insertCustomer(JdbcTemplate jdbc, PasswordEncoder encoder, String email) {
        return jdbc.queryForObject(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, ?, now()) returning id",
                UUID.class,
                email,
                encoder.encode(CUSTOMER_PASSWORD));
    }

    /** Inserts a PUBLISHED Product and returns its id. */
    static UUID insertProduct(JdbcTemplate jdbc, String title) {
        return jdbc.queryForObject(
                "insert into products (title, description, category, price_cents, status, first_published_at)"
                        + " values (?, 'd', 'LAW', 4990, 'PUBLISHED', now()) returning id",
                UUID.class,
                title);
    }

    static UUID insertOrder(
            JdbcTemplate jdbc,
            UUID customerId,
            UUID productId,
            int priceCents,
            String status,
            Instant createdAt,
            Instant expiresAt) {
        UUID id = UUID.randomUUID();
        Timestamp paidAt = "PAID".equals(status) ? Timestamp.from(createdAt.plusSeconds(60)) : null;
        Timestamp expiredAt = "EXPIRED".equals(status) ? Timestamp.from(expiresAt) : null;
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at, paid_at, expired_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                customerId,
                productId,
                priceCents,
                status,
                "pay_" + UUID.randomUUID(),
                Timestamp.from(createdAt),
                Timestamp.from(expiresAt),
                paidAt,
                expiredAt);
        return id;
    }
}
