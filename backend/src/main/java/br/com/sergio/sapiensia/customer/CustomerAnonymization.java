package br.com.sergio.sapiensia.customer;

import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import br.com.sergio.sapiensia.payment.CustomerHasPendingOrderException;
import br.com.sergio.sapiensia.payment.CustomerPaymentRelease;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Anonymizes a Customer's account in place (FR-036, SEC-038, ADR-0007): the row stays, because Orders, Accesses and
 * Download records point to it, but nothing in it identifies the person any more. The append-only financial tables
 * are never touched. The e-mail never reaches a log or the audit trail.
 */
@Service
public class CustomerAnonymization {

    private static final Logger log = LoggerFactory.getLogger(CustomerAnonymization.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final CustomerPaymentRelease paymentRelease;
    private final AuditLog auditLog;
    private final TransactionTemplate transaction;

    CustomerAnonymization(
            JdbcClient jdbc,
            PasswordEncoder passwordEncoder,
            CustomerPaymentRelease paymentRelease,
            AuditLog auditLog,
            TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.paymentRelease = paymentRelease;
        this.auditLog = auditLog;
        this.transaction = transaction;
    }

    /**
     * Not transactional itself: the Argon2 hash is computed before the transaction opens, so it does not hold the row
     * lock and a connection while it runs (same reason as CustomerService.resetPassword).
     *
     * @return the Customer id, or empty (and nothing changes) if no account has this e-mail or it is already
     *     anonymized
     * @throws br.com.sergio.sapiensia.payment.CustomerHasPendingOrderException if the Customer has a pending Order
     */
    public Optional<UUID> anonymize(String email, UUID administratorId, String origin) {
        // a password nobody knows: 32 random bytes, encoded like any password and then discarded
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String unusableHash = passwordEncoder.encode(Base64.getEncoder().encodeToString(secret));
        Optional<UUID> anonymized;
        try {
            anonymized =
                    transaction.execute(status -> anonymizeInTransaction(email, unusableHash, administratorId, origin));
        } catch (CustomerHasPendingOrderException refused) {
            recordFailure(administratorId, refused.customerId(), origin);
            throw refused;
        }
        if (anonymized.isEmpty()) {
            recordFailure(administratorId, null, origin);
        }
        return anonymized;
    }

    /** Written outside the transaction (already rolled back or finished), so the refusal stays on the trail. */
    private void recordFailure(UUID administratorId, UUID customerId, String origin) {
        auditLog.recordIndependent(new AuditEntry(
                AuditAction.ADMIN_CUSTOMER_ANONYMIZED,
                AuditResult.FAILURE,
                administratorId,
                AuditObject.CUSTOMER,
                customerId,
                origin));
    }

    private Optional<UUID> anonymizeInTransaction(
            String email, String unusableHash, UUID administratorId, String origin) {
        String normalized = Emails.normalize(email);
        Optional<UUID> found = jdbc.sql("select id from customers where email = :email and anonymized_at is null")
                .param("email", normalized)
                .query(UUID.class)
                .optional();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        // lock order: the purchase lock first, then the row (OrderService does the same, or the two would deadlock)
        paymentRelease.lock(found.get());
        found = jdbc.sql("select id from customers where email = :email and anonymized_at is null for update")
                .param("email", normalized)
                .query(UUID.class)
                .optional();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        UUID customerId = found.get();
        // refuses a pending Order; nothing has changed yet when it throws
        paymentRelease.release(customerId);
        jdbc.sql("update customers set email = :email, password_hash = :hash, first_login_at = null,"
                        + " session_version = session_version + 1, anonymized_at = now() where id = :id")
                .param("email", "anonimizado-" + UUID.randomUUID() + "@anonimizado.invalid")
                .param("hash", unusableHash)
                .param("id", customerId)
                .update();
        for (String table : new String[] {"email_confirmation_tokens", "password_reset_tokens", "customer_devices"}) {
            jdbc.sql("delete from " + table + " where customer_id = :id")
                    .param("id", customerId)
                    .update();
        }
        auditLog.recordInTransaction(new AuditEntry(
                AuditAction.ADMIN_CUSTOMER_ANONYMIZED,
                AuditResult.SUCCESS,
                administratorId,
                AuditObject.CUSTOMER,
                customerId,
                origin));
        log.info("Customer {} anonymized by administrator {}", customerId, administratorId);
        return Optional.of(customerId);
    }
}
