package br.com.sergio.sapiensia.audit;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.LocalTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/** Daily cleanup (db-scheduler recurring task) of audit events older than app.audit.retention (SEC-037). */
@Configuration(proxyBeanMethods = false)
class AuditRetentionCleanup {

    static final String TASK_NAME = "delete-expired-audit-events";

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionCleanup.class);

    @Bean
    RecurringTask<Void> deleteExpiredAuditEventsTask(AuditCleaner cleaner) {
        return Tasks.recurring(TASK_NAME, Schedules.daily(LocalTime.of(3, 30))).execute((instance, context) -> {
            // only the count is logged
            log.info("Deleted {} expired audit events", cleaner.deleteExpired());
        });
    }

    /** The work of the task, apart from the scheduler so a test can call it. */
    @Component
    static class AuditCleaner {

        private final AuditEvents events;
        private final AuditProperties properties;

        AuditCleaner(AuditEvents events, AuditProperties properties) {
            this.events = events;
            this.properties = properties;
        }

        int deleteExpired() {
            return events.deleteOlderThan(properties.retention());
        }
    }
}
