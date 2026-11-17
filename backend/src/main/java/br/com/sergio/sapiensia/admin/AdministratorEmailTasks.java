package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.alert.AdminAlertType;
import br.com.sergio.sapiensia.alert.AdminAlerts;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Background task of the Administrator's first-access e-mail. The task has no data: it looks up the Administrator when it runs. */
@Configuration(proxyBeanMethods = false)
class AdministratorEmailTasks {

    static final String PASSWORD_SETUP = "administrator-password-setup";
    /** Fixed instance id: scheduling it again while one is pending does nothing, so at most one is pending. */
    static final String BOOTSTRAP_INSTANCE = "bootstrap";

    private static final Logger log = LoggerFactory.getLogger(AdministratorEmailTasks.class);

    private final int maxAttempts;
    private final Duration retryDelay;
    private final AdminAlerts alerts;

    AdministratorEmailTasks(
            @Value("${app.email.max-attempts}") int maxAttempts,
            @Value("${app.email.retry-delay}") Duration retryDelay,
            AdminAlerts alerts) {
        this.alerts = alerts;
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
    }

    @Bean
    OneTimeTask<Void> administratorPasswordSetupTask(AdministratorEmailService emails) {
        return Tasks.oneTime(PASSWORD_SETUP)
                .onFailure(retryThenDrop())
                .execute((instance, context) -> emails.sendPasswordSetup());
    }

    private FailureHandler<Void> retryThenDrop() {
        // the log names only the task: never the e-mail, the token or the hash
        // maxRetries counts the retries after the first run, so total attempts = retries + 1
        return FailureHandler.<Void>maxRetries(maxAttempts - 1)
                .retryEvery(retryDelay)
                .thenRemove(execution -> {
                    log.error("Task {} failed {} times and was dropped", PASSWORD_SETUP, maxAttempts);
                    alerts.recordSafely(AdminAlertType.TASK_GAVE_UP, PASSWORD_SETUP);
                });
    }
}
