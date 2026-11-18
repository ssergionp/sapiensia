package br.com.sergio.sapiensia.customer;

import br.com.sergio.sapiensia.alert.RecurringTaskFailureAlerts;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.LocalTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Daily cleanup (db-scheduler recurring task) of devices not seen for a year and of used or expired links. */
@Configuration(proxyBeanMethods = false)
class StaleDevicesAndTokensCleanup {

    static final String TASK_NAME = "delete-stale-devices-and-tokens";

    private static final Logger log = LoggerFactory.getLogger(StaleDevicesAndTokensCleanup.class);

    @Bean
    RecurringTask<Void> deleteStaleDevicesAndTokensTask(
            KnownDevices devices,
            PasswordResetTokens resetTokens,
            EmailConfirmationTokens confirmationTokens,
            RecurringTaskFailureAlerts failures) {
        Schedule schedule = Schedules.daily(LocalTime.of(3, 15));
        return Tasks.recurring(TASK_NAME, schedule)
                .onFailure(failures.rescheduleAndAlert(TASK_NAME, schedule))
                .execute((instance, context) -> {
                    // only the counts are logged: never tokens, hashes or e-mails
                    log.info(
                            "Deleted {} stale devices, {} password reset links and {} confirmation links",
                            devices.deleteNotSeenWithinCookieMaxAge(),
                            resetTokens.deleteUsedOrExpired(),
                            confirmationTokens.deleteExpired());
                });
    }
}
