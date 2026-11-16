package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.alert.AdminAlertType;
import br.com.sergio.sapiensia.alert.AdminAlerts;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Records the result of a payment event and logs it; shared by the payment and the refund confirmations. */
@Component
class PaymentEventOutcome {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventOutcome.class);

    private final PaymentEventResults results;
    private final Clock clock;
    private final AdminAlerts alerts;
    private final TransactionTemplate transaction;

    PaymentEventOutcome(
            PaymentEventResults results,
            Clock clock,
            AdminAlerts alerts,
            PlatformTransactionManager transactionManager) {
        this.results = results;
        this.clock = clock;
        this.alerts = alerts;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Writes the alert of a result that needs review (GAVE_UP has its own alert); joins the caller's transaction. */
    void recordReviewAlert(UUID paymentEventId, PaymentResult result, UUID orderId) {
        if (result.needsReview() && result != PaymentResult.GAVE_UP) {
            alerts.record(AdminAlertType.PAYMENT_EVENT_NEEDS_REVIEW, result.name(), orderId, paymentEventId);
        }
    }

    /** Records a result decided without a change to the Order; if another execution recorded one first, that one wins. */
    PaymentResult finish(
            UUID paymentEventId, PaymentEvents.Ref ref, PaymentResult result, UUID orderId, String chargeId) {
        // the result and its alert exist together or not at all
        boolean recorded = Boolean.TRUE.equals(transaction.execute(status -> {
            if (!results.record(paymentEventId, result, orderId, clock.instant())) {
                return false;
            }
            recordReviewAlert(paymentEventId, result, orderId);
            return true;
        }));
        if (!recorded) {
            return results.findFinal(paymentEventId).orElse(result);
        }
        logResult(ref, result, orderId, chargeId);
        return result;
    }

    void logResult(PaymentEvents.Ref ref, PaymentResult result, UUID orderId, String chargeId) {
        if (result.needsReview()) {
            log.warn(
                    "Payment event needs review: eventId={} result={} orderId={} chargeId={}",
                    ref.providerEventId(),
                    result,
                    orderId,
                    chargeId);
        } else {
            log.info(
                    "Payment event processed: eventId={} result={} orderId={} chargeId={}",
                    ref.providerEventId(),
                    result,
                    orderId,
                    chargeId);
        }
    }
}
