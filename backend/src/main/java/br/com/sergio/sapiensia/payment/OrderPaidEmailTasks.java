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
 * Background sending of the payment confirmation e-mail (db-scheduler). The task has no data: the Order id is the
 * instance id. A failing send is retried; when the attempts run out the failure is recorded (the Order and its Access
 * stay untouched).
 */
@Configuration(proxyBeanMethods = false)
class OrderPaidEmailTasks {

    static final String SEND = "order-paid-email";

    private static final Logger log = LoggerFactory.getLogger(OrderPaidEmailTasks.class);
    private static final String NOT_A_UUID =
            "Order paid e-mail task has an instance id that is not a UUID; it will not be retried";

    private final int maxAttempts;
    private final Duration retryDelay;

    OrderPaidEmailTasks(
            @Value("${app.email.max-attempts}") int maxAttempts,
            @Value("${app.email.retry-delay}") Duration retryDelay) {
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
    }

    @Bean
    OneTimeTask<Void> orderPaidEmailTask(OrderPaidEmail email) {
        return Tasks.oneTime(SEND, Void.class)
                // maxRetries counts the retries after the first run, so total attempts = retries + 1
                .onFailure(FailureHandler.<Void>maxRetries(maxAttempts - 1)
                        .retryEvery(retryDelay)
                        .thenRemove(execution -> giveUp(
                                email, execution.getExecution().taskInstance.getId())))
                .execute((instance, context) -> {
                    UUID orderId;
                    try {
                        orderId = UUID.fromString(instance.getId());
                    } catch (IllegalArgumentException e) {
                        log.error(NOT_A_UUID);
                        return;
                    }
                    email.send(orderId);
                });
    }

    private void giveUp(OrderPaidEmail email, String instanceId) {
        try {
            email.giveUp(UUID.fromString(instanceId));
        } catch (IllegalArgumentException e) {
            log.error(NOT_A_UUID);
        }
    }
}
