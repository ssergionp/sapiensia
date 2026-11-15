package br.com.sergio.sapiensia.payment;

import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Background sending of the payment confirmation e-mail (db-scheduler). The task has no data: the Order id is the
 * instance id. The handler is a placeholder until the sending itself is implemented.
 */
@Configuration(proxyBeanMethods = false)
class OrderPaidEmailTasks {

    static final String SEND = "order-paid-email";

    @Bean
    OneTimeTask<Void> orderPaidEmailTask() {
        return Tasks.oneTime(SEND, Void.class).execute((instance, context) -> {});
    }
}
