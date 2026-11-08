package br.com.sergio.sapiensia.audit;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Writes the audit trail (SEC-065). Takes only AuditEntry, which has no free text. */
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final AuditEvents events;
    private final Clock clock;
    private final TransactionTemplate independent;

    AuditLog(AuditEvents events, Clock clock, PlatformTransactionManager transactionManager) {
        this.events = events;
        this.clock = clock;
        this.independent = new TransactionTemplate(transactionManager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records in the caller's transaction, so the action and its trail succeed or fail together. Without an open
     * transaction it is refused (a programming error), and a failed insert propagates: no trail, no action.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInTransaction(AuditEntry entry) {
        insert(entry);
    }

    /**
     * Records in its own transaction, so the event survives a rollback of the caller's. Best effort: a failure is
     * logged with the exception class only and does not reach the caller. (A TransactionTemplate is used because a
     * failed insert must roll the transaction back, which a catch inside a proxied method would hide.)
     */
    public void recordIndependent(AuditEntry entry) {
        try {
            independent.executeWithoutResult(status -> insert(entry));
        } catch (RuntimeException e) {
            log.warn("Audit event could not be recorded: {}", e.getClass().getName());
        }
    }

    private void insert(AuditEntry entry) {
        events.insert(OffsetDateTime.now(clock), entry, correlationId(), withoutZone(entry.origin()));
    }

    private static UUID correlationId() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (value != null) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                // falls through to a new id
            }
        }
        return UUID.randomUUID();
    }

    /** An IPv6 scope ("fe80::1%eth0") is not valid for the inet column. */
    private static String withoutZone(String origin) {
        if (origin == null) {
            return null;
        }
        int zone = origin.indexOf('%');
        return zone < 0 ? origin : origin.substring(0, zone);
    }
}
