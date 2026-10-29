package br.com.sergio.sapiensia.customer;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /**
     * Inserts the customer unless the e-mail already exists. Race-free and raises no exception on a duplicate, so
     * the database never logs the e-mail in a constraint-violation message.
     *
     * @return 1 if a row was inserted, 0 if the e-mail was already taken
     */
    @Transactional
    @Modifying
    @Query(
            nativeQuery = true,
            value = "insert into customers (id, email, password_hash) values (gen_random_uuid(), :email, :passwordHash)"
                    + " on conflict (email) do nothing")
    int insertIfAbsent(String email, String passwordHash);
}
