package br.com.sergio.sapiensia.customer;

import java.time.Instant;
import java.util.Optional;
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

    /**
     * Replaces the password of an account whose e-mail is not confirmed yet and restarts its retention period, so
     * the cleanup cannot delete the account right after the customer received a fresh link.
     *
     * @return the customer id, or empty if the account does not exist or is already confirmed
     */
    @Transactional
    @Query(
            nativeQuery = true,
            value = "update customers set password_hash = :passwordHash, created_at = now()"
                    + " where email = :email and email_confirmed_at is null returning id")
    Optional<UUID> replacePasswordIfUnconfirmed(String email, String passwordHash);

    /** Deletes unconfirmed accounts created before the cutoff; their tokens go away by cascade. */
    @Transactional
    @Modifying
    @Query(
            nativeQuery = true,
            value = "delete from customers where email_confirmed_at is null and created_at < :cutoff")
    int deleteUnconfirmedCreatedBefore(Instant cutoff);

    @Query("select c.id from Customer c where c.email = :email")
    Optional<UUID> findIdByEmail(String email);

    @Query("select new br.com.sergio.sapiensia.customer.CustomerCredentials("
            + "c.id, c.email, c.passwordHash, c.emailConfirmedAt) from Customer c where c.email = :email")
    Optional<CustomerCredentials> findCredentialsByEmail(String email);

    @Query("select c.email from Customer c where c.id = :id")
    Optional<String> findEmailById(UUID id);

    @Query("select c.id from Customer c where c.email = :email and c.emailConfirmedAt is not null")
    Optional<UUID> findConfirmedIdByEmail(String email);

    @Query("select c.email from Customer c where c.id = :id and c.emailConfirmedAt is not null")
    Optional<String> findConfirmedEmailById(UUID id);

    @Query("select c.email from Customer c where c.id = :id and c.emailConfirmedAt is null")
    Optional<String> findUnconfirmedEmailById(UUID id);
}
