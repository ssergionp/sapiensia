package br.com.sergio.sapiensia.payment;

import java.util.UUID;

/** The Customer still has a PENDING Order (a live charge), so the account cannot be anonymized; the API answers 409. */
public class CustomerHasPendingOrderException extends RuntimeException {

    private final UUID customerId;

    CustomerHasPendingOrderException(UUID customerId) {
        super(null, null, false, false);
        this.customerId = customerId;
    }

    public UUID customerId() {
        return customerId;
    }
}
