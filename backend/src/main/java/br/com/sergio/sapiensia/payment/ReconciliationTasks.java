package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.alert.RecurringTaskFailureAlerts;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Schedules the nightly reconciliation with Asaas (db-scheduler recurring task, so an overdue night runs after a
 * restart). The cron is in the São Paulo zone; "-" disables the task and db-scheduler cancels its stored execution.
 * An {@link Reconciliation.IncompleteException} propagates on purpose: db-scheduler records the failure and
 * {@link RecurringTaskFailureAlerts} moves the task to the next night (the alert was already recorded by the run) and
 * alerts again if it fails three nights in a row.
 */
@Configuration(proxyBeanMethods = false)
class ReconciliationTasks {

    static final String TASK_NAME = "asaas-reconciliation";

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @Bean
    RecurringTask<Void> asaasReconciliationTask(
            Reconciliation reconciliation,
            RecurringTaskFailureAlerts failures,
            @Value("${app.reconciliation.cron}") String cron) {
        Schedule schedule = Schedules.cron(cron, SAO_PAULO);
        return Tasks.recurring(TASK_NAME, schedule)
                .onFailure(failures.rescheduleAndAlert(TASK_NAME, schedule))
                .execute((instance, context) -> reconciliation.run());
    }
}
