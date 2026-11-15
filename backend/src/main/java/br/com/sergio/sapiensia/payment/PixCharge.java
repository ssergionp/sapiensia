package br.com.sergio.sapiensia.payment;

import java.math.BigDecimal;

/** A charge as the provider has it now. The toString carries only the id and the status. */
public record PixCharge(
        String id, String status, BigDecimal value, String billingType, String externalReference, boolean deleted) {

    public boolean isPaid() {
        return "RECEIVED".equals(status) || "CONFIRMED".equals(status);
    }

    public boolean isAwaitingPayment() {
        return "PENDING".equals(status) || "OVERDUE".equals(status);
    }

    public boolean isRefunded() {
        return "REFUNDED".equals(status);
    }

    /** The refund was asked for but not finished: the provider will say REFUNDED later. */
    public boolean isRefundInProgress() {
        return "REFUND_REQUESTED".equals(status) || "REFUND_IN_PROGRESS".equals(status);
    }

    @Override
    public String toString() {
        return "PixCharge[id=" + id + ", status=" + status + "]";
    }
}
