package br.com.sergio.sapiensia.alert;

import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The failure handler of the recurring tasks (NFR-009): it reschedules the task exactly as db-scheduler's default does
 * and alerts the Administrator on the 3rd, 6th, 12th... failure in a row (intervals that double, so a lost alert
 * does not silence the whole sequence and the e-mails do not come once per run). The {@code admin-alert-dispatch} task
 * does not use it: an alert about the sending of alerts could loop.
 */
@Component
public class RecurringTaskFailureAlerts {

    static final int CONSECUTIVE_FAILURES_TO_ALERT = 3;

    private static final Logger log = LoggerFactory.getLogger(RecurringTaskFailureAlerts.class);

    private final AdminAlerts alerts;

    RecurringTaskFailureAlerts(AdminAlerts alerts) {
        this.alerts = alerts;
    }

    public <T> FailureHandler<T> rescheduleAndAlert(String taskName, Schedule schedule) {
        return new RescheduleAndAlert<>(taskName, schedule, alerts);
    }

    static final class RescheduleAndAlert<T> implements FailureHandler<T> {

        private final String taskName;
        private final Schedule schedule;
        private final AdminAlerts alerts;

        RescheduleAndAlert(String taskName, Schedule schedule, AdminAlerts alerts) {
            this.taskName = taskName;
            this.schedule = schedule;
            this.alerts = alerts;
        }

        /** True on the 3rd, 6th, 12th, 24th... failure: a multiple of 3 whose quotient is a power of two. */
        static boolean shouldAlert(int failures) {
            return failures % CONSECUTIVE_FAILURES_TO_ALERT == 0
                    && Integer.bitCount(failures / CONSECUTIVE_FAILURES_TO_ALERT) == 1;
        }

        String taskName() {
            return taskName;
        }

        @Override
        public void onFailure(ExecutionComplete complete, ExecutionOperations<T> operations) {
            new FailureHandler.OnFailureReschedule<T>(schedule).onFailure(complete, operations);
            // consecutiveFailures counts the failures before this one
            int failures = complete.getExecution().consecutiveFailures + 1;
            if (shouldAlert(failures)) {
                // the log names only the task: never the exception message (ADR-0006)
                log.error("Recurring task {} failed {} times in a row", taskName, failures);
                alerts.recordSafely(AdminAlertType.RECURRING_TASK_FAILING, taskName);
            }
        }
    }
}
