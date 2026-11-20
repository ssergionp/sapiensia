package br.com.sergio.sapiensia.payment;

import java.time.Instant;
import java.util.UUID;

/** An Order as its Customer sees it. {@code pixCopyPaste} is null unless the Order is pending and in time. */
record OrderResponse(
        UUID id,
        UUID productId,
        String productTitle,
        int priceCents,
        OrderStatus status,
        Instant expiresAt,
        long secondsToExpire,
        String pixCopyPaste,
        Instant refundRequestedAt) {

    // never the Pix code
    @Override
    public String toString() {
        return "OrderResponse[id=" + id + ", status=" + status + "]";
    }
}
