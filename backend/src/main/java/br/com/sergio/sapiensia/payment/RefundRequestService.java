package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.alert.AdminAlertType;
import br.com.sergio.sapiensia.alert.AdminAlerts;
import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Customer's Refund request (FR-023). The request, the suspension of the Access of that Order, the audit entry and
 * the Administrator alerts are written in one transaction. The Order stays PAID: the money goes back through the
 * provider, and only {@link RefundConfirmation} marks it as refunded. The comment never reaches the log, the audit
 * trail or an alert (SEC-036).
 */
@Service
class RefundRequestService {

    private static final Logger log = LoggerFactory.getLogger(RefundRequestService.class);
    /** From the third Refund in 12 months (this one plus two others), the Administrator is alerted. */
    private static final int OTHER_REFUNDS_ALERT = 2;
    /** The longest comment, counted on the text as sent (before its spaces are stripped). */
    private static final int MAX_DETAILS = 500;

    /** When the Refund was asked; {@code isNew} is false when the Order already had a request. */
    record Requested(Instant requestedAt, boolean isNew) {}

    private final Orders orders;
    private final RefundRequests requests;
    private final Accesses accesses;
    private final AuditLog audit;
    private final AdminAlerts alerts;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate snapshot;

    RefundRequestService(
            Orders orders,
            RefundRequests requests,
            Accesses accesses,
            AuditLog audit,
            AdminAlerts alerts,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.requests = requests;
        this.accesses = accesses;
        this.audit = audit;
        this.alerts = alerts;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.snapshot = new TransactionTemplate(transactionManager);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    Requested request(UUID customerId, UUID orderId, RefundReason reason, String details) {
        if (details != null && details.length() > MAX_DETAILS) {
            throw new RefundDetailsTooLongException();
        }
        String comment = details == null || details.isBlank() ? null : details.strip();
        Requested requested = transaction.execute(status -> requestInTransaction(customerId, orderId, reason, comment));
        if (requested.isNew()) {
            // after the commit, the id and the closed reason only: never the comment or the e-mail
            log.info("Refund requested: orderId={} reason={}", orderId, reason);
        }
        return requested;
    }

    /**
     * One page of the Administrator's list (FR-023). The page and the count are read in one read-only REPEATABLE READ
     * transaction, so they come from one database snapshot. Nothing is changed and no provider is called.
     */
    AdminRefundRequestPage listForAdministrator(int page, RefundRequestFilter filter) {
        return snapshot.execute(status -> {
            List<AdminRefundRequestItem> items = requests.pageForAdministrator(filter, page);
            long total = requests.countForAdministrator(filter);
            return new AdminRefundRequestPage(
                    items, page, (int) ((total + RefundRequests.ADMIN_PAGE_SIZE - 1) / RefundRequests.ADMIN_PAGE_SIZE));
        });
    }

    private Requested requestInTransaction(UUID customerId, UUID orderId, RefundReason reason, String comment) {
        // the same lock as the purchase and the anonymization: one change at a time per Customer
        orders.lockCustomer(customerId);
        // The anonymization holds this lock until its commit, so a read here already sees anonymized_at.
        if (!orders.customerIsActive(customerId)) {
            throw new CustomerAccountClosedException();
        }
        Orders.Row order = orders.findForCustomer(orderId, customerId).orElseThrow(OrderNotFoundException::new);
        Optional<Instant> existing = requests.requestedAt(orderId);
        if (existing.isPresent()) {
            return new Requested(existing.get(), false);
        }
        if (order.status() != OrderStatus.PAID) {
            throw new RefundNotAvailableException();
        }
        // the database keeps microseconds: the answer of a new request and of a repeated one must be the same date
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!now.isBefore(RefundWindow.closesAt(order.paidAt()))) {
            throw new RefundWindowClosedException();
        }
        if (!requests.insert(UUID.randomUUID(), orderId, customerId, reason, comment, now)) {
            // another request for this Order won despite the lock: answer with the stored one
            return new Requested(requests.requestedAt(orderId).orElseThrow(), false);
        }
        // the Access of THIS Order only: a duplicate purchase never granted one, and the other Order keeps its own
        accesses.suspendByOrder(orderId, now);
        audit.recordInTransaction(new AuditEntry(
                AuditAction.REFUND_REQUESTED, AuditResult.SUCCESS, null, AuditObject.ORDER, orderId, null));
        alerts.record(AdminAlertType.REFUND_REQUESTED, null, orderId, null);
        Instant since = now.atZone(RefundWindow.ZONE).minusMonths(12).toInstant();
        if (requests.otherRefunds(customerId, orderId, since) >= OTHER_REFUNDS_ALERT) {
            alerts.record(AdminAlertType.REFUND_REQUESTS_REPEATED, null, orderId, null);
        }
        return new Requested(now, true);
    }
}
