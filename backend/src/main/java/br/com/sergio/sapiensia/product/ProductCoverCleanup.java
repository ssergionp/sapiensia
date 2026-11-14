package br.com.sergio.sapiensia.product;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Hourly cleanup (db-scheduler recurring task) of the objects recorded in pending_object_deletions: orphan covers and
 * Product files replaced by a newer upload. Only keys older than app.product-cover.orphan-min-age are touched, far
 * above the storage read timeout and the 60-second download link, so an upload that is still running or a link issued
 * before a swap is never caught. The current cover and the current Product file are never deleted.
 */
@Configuration(proxyBeanMethods = false)
class ProductCoverCleanup {

    static final String TASK_NAME = "delete-orphan-cover-objects";

    private static final Logger log = LoggerFactory.getLogger(ProductCoverCleanup.class);

    @Bean
    RecurringTask<Void> deleteOrphanCoverObjectsTask(Cleaner cleaner) {
        return Tasks.recurring(TASK_NAME, Schedules.fixedDelay(Duration.ofHours(1)))
                .execute((instance, context) -> {
                    Cleaner.Result result = cleaner.run();
                    // only counts are logged
                    log.info("Deleted {} pending storage objects ({} failed)", result.deleted(), result.failed());
                });
    }

    /** The work of the task, apart from the scheduler so a test can call it. */
    @Component
    static class Cleaner {

        record Result(int deleted, int failed) {}

        private final PendingObjectDeletions pending;
        private final ProductFileStorage storage;
        private final Duration minAge;

        Cleaner(
                PendingObjectDeletions pending,
                ProductFileStorage storage,
                @Value("${app.product-cover.orphan-min-age}") Duration minAge) {
            this.pending = pending;
            this.storage = storage;
            this.minAge = minAge;
        }

        int deleteOrphans() {
            return run().deleted();
        }

        Result run() {
            int deleted = 0;
            int failed = 0;
            // Extra defense: the current cover and the current Product file are never deleted, whatever the pending
            // list says; their stale records are dropped so they are not looked at again.
            pending.removeStaleCurrentCovers(minAge.toSeconds());
            for (String key : pending.orphanCandidates(minAge.toSeconds())) {
                try {
                    storage.delete(key);
                    deleted++;
                    pending.remove(key);
                } catch (RuntimeException e) {
                    failed++;
                    // never the key or the message: only the class of the failure
                    log.warn(
                            "Pending storage object could not be deleted: {}",
                            e.getClass().getName());
                }
            }
            return new Result(deleted, failed);
        }
    }
}
