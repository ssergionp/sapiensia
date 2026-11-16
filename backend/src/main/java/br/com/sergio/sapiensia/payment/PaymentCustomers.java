package br.com.sergio.sapiensia.payment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The payment provider's customer code of each Customer. The CPF is never stored (ADR-0003). */
@Repository
public class PaymentCustomers {

    private final JdbcClient jdbc;

    PaymentCustomers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> find(UUID customerId) {
        return jdbc.sql("select provider_customer_id from payment_customers where customer_id = :customerId")
                .param("customerId", customerId)
                .query(String.class)
                .optional();
    }

    /** Forgets the link with the person at the provider (anonymization, FR-036); the charges stay with the Orders. */
    public void deleteByCustomerId(UUID customerId) {
        jdbc.sql("delete from payment_customers where customer_id = :customerId")
                .param("customerId", customerId)
                .update();
    }

    /** Keeps the first code saved for the Customer. */
    void save(UUID customerId, String providerCustomerId) {
        jdbc.sql("insert into payment_customers (customer_id, provider_customer_id)"
                        + " values (:customerId, :providerCustomerId)"
                        + " on conflict (customer_id) do nothing")
                .param("customerId", customerId)
                .param("providerCustomerId", providerCustomerId)
                .update();
    }
}
