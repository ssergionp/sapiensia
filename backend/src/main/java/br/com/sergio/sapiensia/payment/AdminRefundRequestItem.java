package br.com.sergio.sapiensia.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * One Refund request in the Administrator's list (FR-023). The e-mail is already masked; {@code details} is the
 * Customer's free text, returned as stored (SEC-036).
 */
record AdminRefundRequestItem(
        UUID orderId,
        String productTitle,
        int priceCents,
        String customerEmail,
        RefundReason reason,
        String details,
        Instant requestedAt,
        Instant paidAt,
        long downloads,
        int otherRefunds,
        String chargeId,
        Instant refundedAt) {

    // never the comment or the e-mail: a logged item or page must not carry personal data (SEC-036)
    @Override
    public String toString() {
        return "AdminRefundRequestItem[orderId=" + orderId + ", reason=" + reason + "]";
    }
}
