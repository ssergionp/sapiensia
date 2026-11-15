package br.com.sergio.sapiensia.payment;

import java.time.Instant;
import java.util.UUID;

/** One Order in the Administrator's list; the e-mail is already masked (FR-020, SEC-010). */
record AdminOrderItem(
        UUID id,
        String productTitle,
        int priceCents,
        OrderStatus status,
        String customerEmail,
        Instant createdAt,
        Instant paidAt) {}
