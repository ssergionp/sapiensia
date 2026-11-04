package br.com.sergio.sapiensia.customer;

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
 * Background e-mail tasks (db-scheduler). The task data is only the customer id: the e-mail and the token are loaded
 * or created when the task runs, so they never sit in the scheduler table.
 */
@Configuration(proxyBeanMethods = false)
class CustomerEmailTasks {

    static final String CONFIRMATION = "customer-email-confirmation";
    static final String PASSWORD_RESET = "customer-password-reset";
    static final String PASSWORD_CHANGED_NOTICE = "customer-password-changed-notice";
    static final String ACCOUNT_EXISTS_NOTICE = "customer-account-exists-notice";

    private static final Logger log = LoggerFactory.getLogger(CustomerEmailTasks.class);

    private final int maxAttempts;
    private final Duration retryDelay;

    CustomerEmailTasks(
            @Value("${app.email.max-attempts}") int maxAttempts,
            @Value("${app.email.retry-delay}") Duration retryDelay) {
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
    }

    @Bean
    OneTimeTask<UUID> customerEmailConfirmationTask(CustomerEmailService emails) {
        return Tasks.oneTime(CONFIRMATION, UUID.class)
                .onFailure(retryThenDrop(CONFIRMATION))
                .execute((instance, context) -> emails.sendConfirmation(instance.getData()));
    }

    @Bean
    OneTimeTask<UUID> customerAccountExistsNoticeTask(CustomerEmailService emails) {
        return Tasks.oneTime(ACCOUNT_EXISTS_NOTICE, UUID.class)
                .onFailure(retryThenDrop(ACCOUNT_EXISTS_NOTICE))
                .execute((instance, context) -> emails.sendAccountExistsNotice(instance.getData()));
    }

    @Bean
    OneTimeTask<UUID> customerPasswordResetTask(CustomerEmailService emails) {
        return Tasks.oneTime(PASSWORD_RESET, UUID.class)
                .onFailure(retryThenDrop(PASSWORD_RESET))
                .execute((instance, context) -> emails.sendPasswordReset(instance.getData()));
    }

    @Bean
    OneTimeTask<UUID> customerPasswordChangedNoticeTask(CustomerEmailService emails) {
        return Tasks.oneTime(PASSWORD_CHANGED_NOTICE, UUID.class)
                .onFailure(retryThenDrop(PASSWORD_CHANGED_NOTICE))
                .execute((instance, context) -> emails.sendPasswordChangedNotice(instance.getData()));
    }

    private FailureHandler<UUID> retryThenDrop(String taskName) {
        // the log names only the task: never the e-mail, the token or the customer data
        return FailureHandler.<UUID>maxRetries(maxAttempts)
                .retryEvery(retryDelay)
                .thenRemove(execution -> log.error("Task {} failed {} times and was dropped", taskName, maxAttempts));
    }
}
