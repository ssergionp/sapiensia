package br.com.sergio.sapiensia.payment;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
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

    private static final Logger log = LoggerFactory.getLogger(OrderExpiration.class);

    @Bean
    RecurringTask<Void> expirePendingOrdersTask(Expirer expirer) {
        return Tasks.recurring(TASK_NAME, Schedules.fixedDelay(Duration.ofMinutes(1)))
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
            for (Orders.Row order : orders.findPendingExpired(clock.instant(), BATCH_SIZE)) {
                if (expire(order)) {
                    expired++;
                } else {
                    postponed++;
                }
            }
            return new Result(expired, postponed);
        }

        /**
         * Cancels the charge and marks the Order EXPIRED. False when the provider is unavailable: the Order stays
         * pending. A refusal (the charge cannot be cancelled, for example already paid) still expires the Order. The
         * marking uses the caller's transaction, if there is one.
         */
        boolean expire(Orders.Row order) {
            try {
                gateway.cancelCharge(order.providerPaymentId());
            } catch (PixGatewayException e) {
                if (e.kind() == PixGatewayException.Kind.UNAVAILABLE) {
                    return false;
                }
                log.warn(
                        "Pix charge could not be cancelled, expiring the order anyway: orderId={} chargeId={}",
                        order.id(),
                        order.providerPaymentId());
            }
            orders.markExpired(order.id(), clock.instant());
            return true;
        }
    }
}
