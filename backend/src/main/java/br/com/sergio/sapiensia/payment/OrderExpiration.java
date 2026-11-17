package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.alert.RecurringTaskFailureAlerts;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Every minute (db-scheduler recurring task) the pending Orders past their time get their Pix charge cancelled at the
 * provider and are marked EXPIRED (ADR-0003). While the provider is unavailable the Order stays pending and the next
 * run tries again.
 */
@Configuration(proxyBeanMethods = false)
class OrderExpiration {

    static final String TASK_NAME = "expire-pending-orders";
    static final int BATCH_SIZE = 50;
    // one charge that keeps failing must not block the queue; a run of failures means the provider is down
    static final int MAX_CONSECUTIVE_OUTAGES = 3;

    private static final Logger log = LoggerFactory.getLogger(OrderExpiration.class);

    @Bean
    RecurringTask<Void> expirePendingOrdersTask(Expirer expirer, RecurringTaskFailureAlerts failures) {
        Schedule schedule = Schedules.fixedDelay(Duration.ofMinutes(1));
        return Tasks.recurring(TASK_NAME, schedule)
                .onFailure(failures.rescheduleAndAlert(TASK_NAME, schedule))
                .execute((instance, context) -> {
                    Expirer.Result result = expirer.run();
                    // only counts are logged
                    if (result.expired() > 0 || result.postponed() > 0) {
                        log.info("Expired {} pending orders ({} postponed)", result.expired(), result.postponed());
                    }
                });
    }

    /** The work of the task, apart from the scheduler so a test can call it. */
    @Component
    static class Expirer {

        record Result(int expired, int postponed) {}

        private final Orders orders;
        private final PixGateway gateway;
        private final Clock clock;

        Expirer(Orders orders, PixGateway gateway, Clock clock) {
            this.orders = orders;
            this.gateway = gateway;
            this.clock = clock;
        }

        Result run() {
            int expired = 0;
            int postponed = 0;
            var due = orders.findPendingExpired(clock.instant(), BATCH_SIZE);
            int consecutiveOutages = 0;
            for (int i = 0; i < due.size(); i++) {
                Outcome outcome = tryExpire(due.get(i));
                if (outcome == Outcome.PROVIDER_UNAVAILABLE) {
                    postponed++;
                    consecutiveOutages++;
                    if (consecutiveOutages >= MAX_CONSECUTIVE_OUTAGES) {
                        // the provider looks down: stop instead of waiting for a timeout on each remaining Order
                        postponed += due.size() - i - 1;
                        break;
                    }
                    continue;
                }
                consecutiveOutages = 0;
                // only the Orders this call moved to EXPIRED count (another call may have done it first)
                if (outcome == Outcome.MARKED) {
                    expired++;
                }
            }
            return new Result(expired, postponed);
        }

        private enum Outcome {
            MARKED,
            ALREADY_EXPIRED,
            PROVIDER_UNAVAILABLE
        }

        /**
         * Cancels the charge and marks the Order EXPIRED. False when the provider is unavailable: the Order stays
         * pending. A refusal (the charge cannot be cancelled, for example already paid) still expires the Order. The
         * marking uses the caller's transaction, if there is one.
         */
        boolean expire(Orders.Row order) {
            return tryExpire(order) != Outcome.PROVIDER_UNAVAILABLE;
        }

        private Outcome tryExpire(Orders.Row order) {
            // the Order may have been paid since it was listed: never cancel the charge of a paid Order (ADR-0003)
            boolean stillPending = orders.findById(order.id())
                    .map(current -> current.status() == OrderStatus.PENDING)
                    .orElse(false);
            if (!stillPending) {
                return Outcome.ALREADY_EXPIRED;
            }
            try {
                gateway.cancelCharge(order.providerPaymentId());
            } catch (PixGatewayException e) {
                if (e.kind() == PixGatewayException.Kind.UNAVAILABLE) {
                    return Outcome.PROVIDER_UNAVAILABLE;
                }
                log.warn(
                        "Pix charge could not be cancelled, expiring the order anyway: orderId={} chargeId={}",
                        order.id(),
                        order.providerPaymentId());
            } catch (RuntimeException e) {
                // an unexpected failure on one Order must not stop the others; only the class name is logged
                log.warn(
                        "Pix charge cancellation failed unexpectedly: orderId={} error={}",
                        order.id(),
                        e.getClass().getSimpleName());
                return Outcome.PROVIDER_UNAVAILABLE;
            }
            return orders.markExpired(order.id(), clock.instant()) ? Outcome.MARKED : Outcome.ALREADY_EXPIRED;
        }
    }
}
