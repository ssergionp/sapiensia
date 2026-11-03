package br.com.sergio.sapiensia.customer;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.LocalTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Daily cleanup (db-scheduler recurring task) of accounts whose e-mail was never confirmed. */
@Configuration(proxyBeanMethods = false)
class UnconfirmedCustomersCleanup {

    static final String TASK_NAME = "delete-unconfirmed-customers";

    private static final Logger log = LoggerFactory.getLogger(UnconfirmedCustomersCleanup.class);

    @Bean
    RecurringTask<Void> deleteUnconfirmedCustomersTask(ObjectProvider<CustomerService> customers) {
        // resolved at run time: CustomerService needs the scheduler client, which needs the task beans (a cycle)
        return Tasks.recurring(TASK_NAME, Schedules.daily(LocalTime.of(3, 0))).execute((instance, context) -> {
            // only the count is logged: never e-mails
            log.info("Deleted {} unconfirmed customers", customers.getObject().deleteUnconfirmedOlderThanRetention());
        });
    }
}
