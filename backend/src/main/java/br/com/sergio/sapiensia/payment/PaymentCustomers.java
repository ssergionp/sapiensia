package br.com.sergio.sapiensia.payment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The payment provider's customer code of each Customer. The CPF is never stored (ADR-0003). */
@Repository
class PaymentCustomers {

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
