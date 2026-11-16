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

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID customerId) {
        orders.lockCustomer(customerId);
        if (orders.hasPending(customerId)) {
            throw new CustomerHasPendingOrderException(customerId);
        }
        paymentCustomers.deleteByCustomerId(customerId);
    }
}
