package br.com.sergio.sapiensia.payment;

import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Background processing of a stored payment event (db-scheduler). The task has no data: the event id is the instance
 * id, so the runbook can reschedule an event with plain SQL. While the provider cannot tell yet, the task is retried;
 * when the attempts run out the event is marked GAVE_UP (the event itself stays) and only then the task is removed; if
 * the GAVE_UP cannot be recorded the task is kept and retried.
 */
@Configuration(proxyBeanMethods = false)
class PaymentEventTasks {

    static final String PROCESS = "process-payment-event";

    /** The events that RefundConfirmation handles; every other processed type is a payment. */
    private static final Set<String> REFUND_TYPES =
            Set.of("PAYMENT_REFUNDED", "PAYMENT_PARTIALLY_REFUNDED", "BALANCE_VALUE_BLOCKED");

    private static final Logger log = LoggerFactory.getLogger(PaymentEventTasks.class);

    private final int maxAttempts;
    private final Duration retryDelay;

    PaymentEventTasks(
            @Value("${app.orders.payment-processing.max-attempts}") int maxAttempts,
            @Value("${app.orders.payment-processing.retry-delay}") Duration retryDelay) {
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
    }

    @Bean
    OneTimeTask<Void> processPaymentEventTask(
            PaymentConfirmation confirmation, RefundConfirmation refund, PaymentEvents events) {
        return Tasks.oneTime(PROCESS, Void.class)
                // maxRetries counts the retries after the first run, so total attempts = retries + 1
                .onFailure(FailureHandler.<Void>maxRetries(maxAttempts - 1)
                        .retryEvery(retryDelay)
                        .then(
                                (complete, ops) -> {
                                    UUID eventId;
                                    try {
                                        eventId = UUID.fromString(complete.getExecution()
                                                .taskInstance
                                                .getId());
                                    } catch (IllegalArgumentException e) {
                                        log.error("Payment event task dropped with an instance id that is not a UUID");
                                        ops.remove();
                                        return;
                                    }
                                    try {
                                        confirmation.giveUp(eventId);
                                    } catch (RuntimeException e) {
                                        // the event has no result yet: keep the task, or nobody would ever be told
                                        log.error(
                                                "Payment event give-up could not be recorded, will retry: eventId={} error={}",
                                                eventId,
                                                e.getClass().getSimpleName());
                                        ops.reschedule(
                                                complete, complete.getTimeDone().plus(retryDelay));
                                        return;
                                    }
                                    ops.remove();
                                },
                                // the default listener logs "Removing." even when the handler above rescheduled
                                complete -> {}))
                .execute((instance, context) -> {
                    UUID eventId;
                    try {
                        eventId = UUID.fromString(instance.getId());
                    } catch (IllegalArgumentException e) {
                        log.error("Payment event task has an instance id that is not a UUID; it will not be retried");
                        return;
                    }
                    String type = events.findRef(eventId)
                            .map(PaymentEvents.Ref::eventType)
                            .orElseThrow(PaymentConfirmation.PaymentNotReadyException::new);
                    if (REFUND_TYPES.contains(type)) {
                        refund.process(eventId);
                    } else {
                        confirmation.process(eventId);
                    }
                });
    }
}
