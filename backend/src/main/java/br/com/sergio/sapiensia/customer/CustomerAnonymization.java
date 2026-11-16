package br.com.sergio.sapiensia.customer;

import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import br.com.sergio.sapiensia.payment.PaymentCustomers;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final PaymentCustomers paymentCustomers;
    private final AuditLog auditLog;

    CustomerAnonymization(
            JdbcClient jdbc, PasswordEncoder passwordEncoder, PaymentCustomers paymentCustomers, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.paymentCustomers = paymentCustomers;
        this.auditLog = auditLog;
    }

    /**
     * @return the Customer id, or empty (and nothing changes) if no account has this e-mail or it is already
     *     anonymized
     */
    @Transactional
    public Optional<UUID> anonymize(String email, UUID administratorId, String origin) {
        Optional<UUID> found = jdbc.sql(
                        "select id from customers where email = :email and anonymized_at is null for update")
                .param("email", Emails.normalize(email))
                .query(UUID.class)
                .optional();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        UUID customerId = found.get();
        // a password nobody knows: 32 random bytes, encoded like any password and then discarded
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        jdbc.sql("update customers set email = :email, password_hash = :hash, first_login_at = null,"
                        + " session_version = session_version + 1, anonymized_at = now() where id = :id")
                .param("email", "anonimizado-" + UUID.randomUUID() + "@anonimizado.invalid")
                .param("hash", passwordEncoder.encode(Base64.getEncoder().encodeToString(secret)))
                .param("id", customerId)
                .update();
        for (String table : new String[] {"email_confirmation_tokens", "password_reset_tokens", "customer_devices"}) {
            jdbc.sql("delete from " + table + " where customer_id = :id")
                    .param("id", customerId)
                    .update();
        }
        paymentCustomers.deleteByCustomerId(customerId);
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
