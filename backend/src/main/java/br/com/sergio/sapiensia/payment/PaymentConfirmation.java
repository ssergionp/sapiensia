package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.alert.AdminAlertType;
import br.com.sergio.sapiensia.alert.AdminAlerts;
import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only place that marks an Order as paid and grants the Access (FR-024, SEC-026). The webhook only says which
 * charge to look at; the state, the value and the reference come from the provider. The provider is called outside any
 * transaction; the payment, the Access, the audit entry and the result are written in one.
 */
@Service
class PaymentConfirmation {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfirmation.class);
    static final Pattern CHARGE_ID = Pattern.compile("[A-Za-z0-9_]{1,64}");

    /** The provider cannot tell yet; nothing was recorded and the task must try again. */
    static final class PaymentNotReadyException extends RuntimeException {
        PaymentNotReadyException() {
            super(null, null, false, false);
        }
    }

    private final PaymentEvents events;
    private final PaymentEventResults results;
    private final PaymentEventOutcome outcome;
    private final Orders orders;
    private final Accesses accesses;
    private final PixGateway gateway;
    private final AuditLog audit;
    private final Clock clock;
    private final SchedulerClient scheduler;
    private final AdminAlerts alerts;
    private final TransactionTemplate transaction;

    PaymentConfirmation(
            PaymentEvents events,
            PaymentEventResults results,
            PaymentEventOutcome outcome,
            Orders orders,
            Accesses accesses,
            PixGateway gateway,
            AuditLog audit,
            Clock clock,
            @Lazy SchedulerClient scheduler,
            AdminAlerts alerts,
            PlatformTransactionManager transactionManager) {
        this.events = events;
        this.results = results;
        this.outcome = outcome;
        this.orders = orders;
        this.accesses = accesses;
        this.gateway = gateway;
        this.audit = audit;
        this.clock = clock;
        this.scheduler = scheduler;
        this.alerts = alerts;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Returns the result recorded for the event (the existing one when it already had a final result). */
    PaymentResult process(UUID paymentEventId) {
        Optional<PaymentResult> existing = results.findFinal(paymentEventId);
        if (existing.isPresent()) {
            return existing.get();
        }
        PaymentEvents.Ref ref = events.findRef(paymentEventId).orElseThrow(PaymentNotReadyException::new);
        String chargeId = ref.chargeId();
        if (chargeId == null || !CHARGE_ID.matcher(chargeId).matches()) {
            return finish(paymentEventId, ref, PaymentResult.INVALID_PAYLOAD, null, null);
        }
        Optional<Orders.Row> found = orders.findByProviderPaymentId(chargeId);
        if (found.isEmpty()) {
            return finish(paymentEventId, ref, PaymentResult.NO_ORDER, null, chargeId);
        }
        Orders.Row order = found.get();
        if (order.status() == OrderStatus.PAID) {
            return finish(paymentEventId, ref, PaymentResult.ALREADY_PAID, order.id(), chargeId);
        }
        if (order.status() == OrderStatus.REFUNDED) {
            // a refund that came first wins: the Order never goes back to PAID and no Access is granted
            return finish(paymentEventId, ref, PaymentResult.ALREADY_REFUNDED, order.id(), chargeId);
        }
        PixCharge charge;
        try {
            charge = gateway.readCharge(chargeId);
        } catch (PixGatewayException e) {
            if (e.kind() == PixGatewayException.Kind.REFUSED) {
                return finish(paymentEventId, ref, PaymentResult.CHARGE_NOT_FOUND, order.id(), chargeId);
            }
            log.warn(
                    "Pix call failed: step={} kind={} eventId={} orderId={}",
                    e.step(),
                    e.kind(),
                    ref.providerEventId(),
                    order.id());
            throw new PaymentNotReadyException();
        }
        PaymentResult refusal = check(order, chargeId, charge);
        if (refusal != null) {
            return finish(paymentEventId, ref, refusal, order.id(), chargeId);
        }
        PaymentResult result = transaction.execute(status -> pay(paymentEventId, order));
        if (result == PaymentResult.ALREADY_PAID || result == PaymentResult.ALREADY_REFUNDED) {
            // another execution paid or refunded the Order between our read and our update
            return finish(paymentEventId, ref, result, order.id(), chargeId);
        }
        outcome.logResult(ref, result, order.id(), chargeId);
        return result;
    }

    /** The first requirement the charge fails, in the order of the plan; null when it can pay the Order. */
    private PaymentResult check(Orders.Row order, String chargeId, PixCharge charge) {
        if (!chargeId.equals(charge.id())) {
            return PaymentResult.CHARGE_ID_MISMATCH;
        }
        if (!order.id().toString().equals(charge.externalReference())) {
            return PaymentResult.REFERENCE_MISMATCH;
        }
        if (!"PIX".equals(charge.billingType())) {
            return PaymentResult.NOT_PIX;
        }
        if (charge.value() == null || charge.value().compareTo(BigDecimal.valueOf(order.priceCents(), 2)) != 0) {
            return PaymentResult.AMOUNT_MISMATCH;
        }
        if (charge.isAwaitingPayment()) {
            throw new PaymentNotReadyException();
        }
        if (!charge.isPaid()) {
            return PaymentResult.NOT_PAID;
        }
        return null;
    }

    private PaymentResult pay(UUID paymentEventId, Orders.Row order) {
        Instant now = clock.instant();
        if (!orders.markPaid(order.id(), now)) {
            boolean refunded = orders.findById(order.id())
                    .map(current -> current.status() == OrderStatus.REFUNDED)
                    .orElse(false);
            return refunded ? PaymentResult.ALREADY_REFUNDED : PaymentResult.ALREADY_PAID;
        }
        boolean granted = accesses.grant(order.customerId(), order.productId(), order.id(), now);
        if (granted) {
            // same transaction as the payment: the e-mail exists if and only if the payment was committed
            scheduler.scheduleIfNotExists(
                    new TaskInstance<Void>(OrderPaidEmailTasks.SEND, order.id().toString()), now);
            audit.recordInTransaction(new AuditEntry(
                    AuditAction.ACCESS_GRANTED, AuditResult.SUCCESS, null, AuditObject.ORDER, order.id(), null));
        }
        PaymentResult result = granted ? PaymentResult.PAID : PaymentResult.DUPLICATE_PURCHASE;
        results.record(paymentEventId, result, order.id(), now);
        outcome.recordReviewAlert(paymentEventId, result, order.id());
        return result;
    }

    private PaymentResult finish(
            UUID paymentEventId, PaymentEvents.Ref ref, PaymentResult result, UUID orderId, String chargeId) {
        return outcome.finish(paymentEventId, ref, result, orderId, chargeId);
    }

    /** Records that the retries ran out (not final: a later reprocessing may still decide). */
    void giveUp(UUID paymentEventId) {
        Optional<PaymentEvents.Ref> ref = events.findRef(paymentEventId);
        if (ref.isEmpty() || results.findFinal(paymentEventId).isPresent()) {
            return;
        }
        String chargeId = ref.get().chargeId();
        boolean valid = chargeId != null && CHARGE_ID.matcher(chargeId).matches();
        UUID orderId = valid
                ? orders.findByProviderPaymentId(chargeId).map(Orders.Row::id).orElse(null)
                : null;
        // the result and its alert exist together or not at all
        boolean recorded = Boolean.TRUE.equals(transaction.execute(status -> {
            if (!results.record(paymentEventId, PaymentResult.GAVE_UP, orderId, clock.instant())) {
                return false;
            }
            alerts.record(AdminAlertType.PAYMENT_EVENT_GAVE_UP, null, orderId, paymentEventId);
            return true;
        }));
        if (recorded) {
            log.error(
                    "Payment event processing gave up: eventId={} orderId={} chargeId={}",
                    ref.get().providerEventId(),
                    orderId,
                    valid ? chargeId : null);
        }
    }
}
