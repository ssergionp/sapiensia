package br.com.sergio.sapiensia.admin;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Creates the single Administrator from the configured e-mail (no initial password) and schedules the first-access
 * e-mail. Idempotent and safe with several instances: an advisory lock serializes runs and the insert happens only
 * while the table is empty. Logs only result words, never the e-mail.
 */
@Component
public class AdministratorBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdministratorBootstrap.class);

    private final Administrators administrators;
    private final SchedulerClient scheduler;
    private final ConfiguredAdministrator configured;
    private final Duration minInterval;

    AdministratorBootstrap(
            Administrators administrators,
            SchedulerClient scheduler,
            ConfiguredAdministrator configured,
            @Value("${app.admin.password-link-min-interval}") Duration minInterval) {
        this.administrators = administrators;
        this.scheduler = scheduler;
        this.configured = configured;
        this.minInterval = minInterval;
    }

    @Transactional
    public BootstrapResult bootstrap() {
        Optional<String> email = configured.email();
        if (email.isEmpty()) {
            log.warn("administrator bootstrap: skipped, APP_ADMIN_EMAIL is empty or not a valid e-mail");
            return BootstrapResult.SKIPPED_NOT_CONFIGURED;
        }
        administrators.lockBootstrap();
        Optional<Administrators.Row> existing = administrators.findOnly(minInterval);
        if (existing.isEmpty()) {
            Optional<UUID> id = administrators.insertIfEmpty(email.get());
            // the advisory lock makes the insert always succeed here
            id.ifPresent(this::scheduleAfterCommit);
            log.info("administrator bootstrap: created");
            return BootstrapResult.CREATED;
        }
        Administrators.Row row = existing.get();
        if (!row.email().equals(email.get())) {
            log.warn("administrator bootstrap: configured e-mail differs from the existing administrator; ignored");
            return BootstrapResult.CONFIG_DIFFERS;
        }
        if (row.passwordSet()) {
            log.info("administrator bootstrap: exists");
            return BootstrapResult.EXISTS;
        }
        if (row.linkSentRecently()) {
            log.info("administrator bootstrap: pending");
            return BootstrapResult.PENDING_RECENTLY_SENT;
        }
        scheduleAfterCommit(row.id());
        log.info("administrator bootstrap: pending, e-mail scheduled");
        return BootstrapResult.PENDING_EMAIL_SCHEDULED;
    }

    /** Schedules after the commit, so the task never runs before the Administrator row is visible. */
    private void scheduleAfterCommit(UUID administratorId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            schedule(administratorId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                schedule(administratorId);
            }
        });
    }

    private void schedule(UUID administratorId) {
        scheduler.scheduleIfNotExists(
                new TaskInstance<>(
                        AdministratorEmailTasks.PASSWORD_SETUP,
                        AdministratorEmailTasks.BOOTSTRAP_INSTANCE,
                        administratorId),
                Instant.now());
    }
}
