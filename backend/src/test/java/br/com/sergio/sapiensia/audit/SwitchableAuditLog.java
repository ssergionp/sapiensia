package br.com.sergio.sapiensia.audit;

import java.time.Clock;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test double of the audit writer: it writes like the real one until {@link #failWrites(boolean)} turns the failure
 * on. It lets a test prove "no trail, no action" without changing the database schema.
 */
public class SwitchableAuditLog extends AuditLog {

    private volatile boolean failing;

    SwitchableAuditLog(AuditEvents events, Clock clock, PlatformTransactionManager transactionManager) {
        super(events, clock, transactionManager);
    }

    public void failWrites(boolean failing) {
        this.failing = failing;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInTransaction(AuditEntry entry) {
        if (failing) {
            throw new DataAccessResourceFailureException("audit writer is down");
        }
        super.recordInTransaction(entry);
    }
}
