package br.com.sergio.sapiensia.payment;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets the anonymization (FR-036, SEC-038) forget the Customer at the payment provider. It waits for a purchase in
 * flight (same lock as OrderService) and refuses while a charge is live, so no payment can arrive for an account
 * nobody can reach.
 */
@Component
public class CustomerPaymentRelease {

    private final Orders orders;
    private final PaymentCustomers paymentCustomers;

    CustomerPaymentRelease(Orders orders, PaymentCustomers paymentCustomers) {
        this.orders = orders;
        this.paymentCustomers = paymentCustomers;
    }

    /**
     * Waits for a purchase in flight. It must come before the Customer row lock: OrderService takes this lock first
     * and then needs the row, so the opposite order would deadlock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID customerId) {
        orders.lockCustomer(customerId);
    }

    /** Needs {@link #lock} first in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID customerId) {
        if (orders.hasPending(customerId)) {
            throw new CustomerHasPendingOrderException(customerId);
        }
        paymentCustomers.deleteByCustomerId(customerId);
    }
}
