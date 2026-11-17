package br.com.sergio.sapiensia.alert;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** NFR-009: every recurring task but the alert dispatch alerts the Administrator after repeated failures. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RecurringTaskFailureWiringTest {

    private static final String DISPATCH = "admin-alert-dispatch";

    @Autowired
    List<RecurringTask<?>> tasks;

    @Test
    void shouldAlertOnRepeatedFailuresOfEveryRecurringTaskButTheAlertDispatch() {
        assertThat(tasks)
                .extracting(RecurringTask::getName)
                .contains(
                        DISPATCH,
                        "expire-pending-orders",
                        "asaas-reconciliation",
                        "delete-orphan-cover-objects",
                        "delete-unconfirmed-customers",
                        "delete-expired-audit-events");

        for (RecurringTask<?> task : tasks) {
            if (task.getName().equals(DISPATCH)) {
                assertThat(task.getFailureHandler())
                        .isNotInstanceOf(RecurringTaskFailureAlerts.RescheduleAndAlert.class);
            } else {
                assertThat(task.getFailureHandler())
                        .as(task.getName())
                        .isInstanceOfSatisfying(
                                RecurringTaskFailureAlerts.RescheduleAndAlert.class,
                                handler -> assertThat(handler.taskName()).isEqualTo(task.getName()));
            }
        }
    }
}
