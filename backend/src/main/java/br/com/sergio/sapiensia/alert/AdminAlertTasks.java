package br.com.sergio.sapiensia.alert;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The recurring db-scheduler task that sends the pending Administrator alerts (ADR-0005). A recurring task, not one
 * task per alert, so no row is left without a task. It only calls the dispatch and has no failure handler that records
 * an alert: an alert about an alert could loop.
 */
@Configuration(proxyBeanMethods = false)
class AdminAlertTasks {

    static final String TASK_NAME = "admin-alert-dispatch";

    @Bean
    RecurringTask<Void> adminAlertDispatchTask(
            AdminAlertDispatch dispatch, @Value("${app.alerts.dispatch-interval}") Duration interval) {
        return Tasks.recurring(TASK_NAME, Schedules.fixedDelay(interval)).execute((instance, context) -> {
            dispatch.run();
        });
    }
}
