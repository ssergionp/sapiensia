package br.com.sergio.sapiensia.payment;

import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Background processing of a stored payment event (db-scheduler). The task has no data: the event id is the instance
 * id, so the runbook can reschedule an event with plain SQL. While the provider cannot tell yet, the task is retried;
 * when the attempts run out the event is marked GAVE_UP (the event itself stays).
 */
@Configuration(proxyBeanMethods = false)
class PaymentEventTasks {

    static final String PROCESS = "process-payment-event";

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
    OneTimeTask<Void> processPaymentEventTask(PaymentConfirmation confirmation) {
        return Tasks.oneTime(PROCESS, Void.class)
                // maxRetries counts the retries after the first run, so total attempts = retries + 1
                .onFailure(FailureHandler.<Void>maxRetries(maxAttempts - 1)
                        .retryEvery(retryDelay)
                        .thenRemove(execution -> giveUp(
                                confirmation,
                                execution.getExecution().taskInstance.getId())))
                .execute((instance, context) -> {
                    UUID eventId;
                    try {
                        eventId = UUID.fromString(instance.getId());
                    } catch (IllegalArgumentException e) {
                        log.error("Payment event task has an instance id that is not a UUID; it will not be retried");
                        return;
                    }
                    confirmation.process(eventId);
                });
    }

    private void giveUp(PaymentConfirmation confirmation, String instanceId) {
        try {
            confirmation.giveUp(UUID.fromString(instanceId));
        } catch (IllegalArgumentException e) {
            log.error("Payment event task dropped with an instance id that is not a UUID");
        }
    }
}
