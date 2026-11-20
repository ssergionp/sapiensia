package br.com.sergio.sapiensia.payment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One line of the Customer's list of Orders; {@code status} is the state shown, never the Pix code (FR-020). */
record OrderSummary(
        UUID id,
        String productTitle,
        int priceCents,
        OrderStatus status,
        Instant createdAt,
        Instant expiresAt,
        LocalDate refundableUntil,
        Instant refundRequestedAt) {}
