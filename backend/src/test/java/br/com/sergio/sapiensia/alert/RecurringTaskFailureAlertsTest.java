package br.com.sergio.sapiensia.alert;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** NFR-009: a recurring task that fails three times in a row alerts the Administrator, once per sequence. */
class RecurringTaskFailureAlertsTest {

    private static final Instant START = Instant.parse("2026-11-16T03:00:00Z");
    private static final Instant DONE = START.plusSeconds(2);

    private final AdminAlerts alerts = mock(AdminAlerts.class);

    @SuppressWarnings("unchecked")
    private final ExecutionOperations<Void> ops = mock(ExecutionOperations.class);

    private final FailureHandler<Void> handler =
            new RecurringTaskFailureAlerts(alerts).rescheduleAndAlert("some-task", FixedDelay.ofMinutes(1));

    private ExecutionComplete failureAfter(int previousFailures) {
        Execution execution = new Execution(
                START,
                new TaskInstance<Void>("some-task", "id"),
                true,
                "picker",
                null,
                null,
                previousFailures,
                START,
                1L);
        return ExecutionComplete.failure(execution, START, DONE, new RuntimeException("x"));
    }

    @Test
    @DisplayName("NFR-009: a 3ª falha seguida reagenda e alerta o Administrador")
    void shouldAlertOnTheThirdConsecutiveFailure() {
        ExecutionComplete complete = failureAfter(2);

        handler.onFailure(complete, ops);

        verify(alerts).recordSafely(AdminAlertType.RECURRING_TASK_FAILING, "some-task");
        verify(ops).reschedule(complete, DONE.plus(Duration.ofMinutes(1)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @DisplayName("NFR-009: antes da 3ª falha seguida só reagenda")
    void shouldNotAlertBeforeTheThirdFailure(int previousFailures) {
        ExecutionComplete complete = failureAfter(previousFailures);

        handler.onFailure(complete, ops);

        verifyNoInteractions(alerts);
        verify(ops).reschedule(complete, DONE.plus(Duration.ofMinutes(1)));
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 10})
    @DisplayName("NFR-009: depois da 3ª falha seguida não alerta de novo")
    void shouldNotAlertAgainAfterTheThirdFailure(int previousFailures) {
        ExecutionComplete complete = failureAfter(previousFailures);

        handler.onFailure(complete, ops);

        verifyNoInteractions(alerts);
        verify(ops).reschedule(complete, DONE.plus(Duration.ofMinutes(1)));
    }
}
