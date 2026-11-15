package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only place that marks an Order as refunded and cancels its Access (FR-023, FR-025, SEC-026). The webhook only
 * says which charge to look at: nothing is cancelled unless the provider, asked right now, shows that very charge,
 * for this Order, as REFUNDED. The provider is called outside any transaction; the refund, the cancellation, the audit
 * entry and the result are written in one. A MED (a balance block) or a partial refund alone is only recorded.
 */
@Service
class RefundConfirmation {

    private static final Logger log = LoggerFactory.getLogger(RefundConfirmation.class);
    private static final String BALANCE_BLOCKED_EVENT = "BALANCE_VALUE_BLOCKED";
    private static final String REFUND_EVENT = "PAYMENT_REFUNDED";
    private static final String PARTIAL_REFUND_EVENT = "PAYMENT_PARTIALLY_REFUNDED";
    private static final String MED_TYPE = "PIX_INFRACTION";

    private final PaymentEvents events;
    private final PaymentEventResults results;
    private final PaymentEventOutcome outcome;
    private final Orders orders;
    private final Accesses accesses;
    private final PixGateway gateway;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate transaction;

    RefundConfirmation(
            PaymentEvents events,
            PaymentEventResults results,
            PaymentEventOutcome outcome,
            Orders orders,
            Accesses accesses,
            PixGateway gateway,
            AuditLog audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.events = events;
        this.results = results;
        this.outcome = outcome;
        this.orders = orders;
        this.accesses = accesses;
        this.gateway = gateway;
        this.audit = audit;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Returns the result recorded for the event (the existing one when it already had a final result). */
    PaymentResult process(UUID paymentEventId) {
        Optional<PaymentResult> existing = results.findFinal(paymentEventId);
        if (existing.isPresent()) {
            return existing.get();
        }
        PaymentEvents.Ref ref =
                events.findRef(paymentEventId).orElseThrow(PaymentConfirmation.PaymentNotReadyException::new);
        boolean balanceBlock = BALANCE_BLOCKED_EVENT.equals(ref.eventType());
        String chargeId = ref.chargeId();
        boolean validCharge = chargeId != null
                && PaymentConfirmation.CHARGE_ID.matcher(chargeId).matches();
        if (balanceBlock && !MED_TYPE.equals(ref.balanceType())) {
            return outcome.finish(
                    paymentEventId, ref, PaymentResult.BALANCE_BLOCKED, null, validCharge ? chargeId : null);
        }
        if (!validCharge) {
            return outcome.finish(
                    paymentEventId,
                    ref,
                    balanceBlock ? PaymentResult.MED_WITHOUT_CHARGE : PaymentResult.INVALID_PAYLOAD,
                    null,
                    null);
        }
        Optional<Orders.Row> found = orders.findByProviderPaymentId(chargeId);
        if (found.isEmpty()) {
            return outcome.finish(paymentEventId, ref, PaymentResult.NO_ORDER, null, chargeId);
        }
        Orders.Row order = found.get();
        if (order.status() == OrderStatus.REFUNDED) {
            return outcome.finish(paymentEventId, ref, PaymentResult.ALREADY_REFUNDED, order.id(), chargeId);
        }
        PixCharge charge;
        try {
            charge = gateway.readCharge(chargeId);
        } catch (PixGatewayException e) {
            if (e.kind() == PixGatewayException.Kind.REFUSED) {
                return outcome.finish(paymentEventId, ref, PaymentResult.CHARGE_NOT_FOUND, order.id(), chargeId);
            }
            log.warn(
                    "Pix call failed: step={} kind={} eventId={} orderId={}",
                    e.step(),
                    e.kind(),
                    ref.providerEventId(),
                    order.id());
            throw new PaymentConfirmation.PaymentNotReadyException();
        }
        if (!chargeId.equals(charge.id())) {
            return outcome.finish(paymentEventId, ref, PaymentResult.CHARGE_ID_MISMATCH, order.id(), chargeId);
        }
        if (!order.id().toString().equals(charge.externalReference())) {
            return outcome.finish(paymentEventId, ref, PaymentResult.REFERENCE_MISMATCH, order.id(), chargeId);
        }
        // a refund notice can beat the charge update at Asaas: retry like a payment (ends in GAVE_UP, reprocessable)
        if (charge.isRefundInProgress()
                || (charge.isPaid() && REFUND_EVENT.equals(ref.eventType()))) {
            throw new PaymentConfirmation.PaymentNotReadyException();
        }
        if (!charge.isRefunded()) {
            PaymentResult notRefunded = balanceBlock
                    ? PaymentResult.MED_BLOCKED
                    : PARTIAL_REFUND_EVENT.equals(ref.eventType())
                            ? PaymentResult.PARTIAL_REFUND
                            : PaymentResult.NOT_REFUNDED;
            return outcome.finish(paymentEventId, ref, notRefunded, order.id(), chargeId);
        }
        PaymentResult result = transaction.execute(status -> refund(paymentEventId, order));
        if (result == PaymentResult.ALREADY_REFUNDED) {
            // another execution refunded the Order between our read and our update
            return outcome.finish(paymentEventId, ref, result, order.id(), chargeId);
        }
        outcome.logResult(ref, result, order.id(), chargeId);
        return result;
    }

    private PaymentResult refund(UUID paymentEventId, Orders.Row order) {
        Instant now = clock.instant();
        if (!orders.markRefunded(order.id(), now)) {
            return PaymentResult.ALREADY_REFUNDED;
        }
        // the Access of THIS Order only: a duplicate purchase never granted one, and the other Order keeps its own
        if (accesses.cancelByOrder(order.id(), now)) {
            audit.recordInTransaction(new AuditEntry(
                    AuditAction.ACCESS_CANCELLED, AuditResult.SUCCESS, null, AuditObject.ORDER, order.id(), null));
        }
        results.record(paymentEventId, PaymentResult.REFUNDED, order.id(), now);
        return PaymentResult.REFUNDED;
    }
}
