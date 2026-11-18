package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

/** SEC-031: the give-up is recorded before the task is removed; if it cannot be recorded, the task stays. */
class PaymentEventTasksTest {

    private static final Instant START = Instant.parse("2026-11-16T03:00:00Z");
    private static final Instant DONE = START.plusSeconds(2);
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    private final PaymentConfirmation confirmation = mock(PaymentConfirmation.class);
    private final RefundConfirmation refund = mock(RefundConfirmation.class);
    private final PaymentEvents events = mock(PaymentEvents.class);

    @SuppressWarnings("unchecked")
    private final ExecutionOperations<Void> ops = mock(ExecutionOperations.class);

    private final OneTimeTask<Void> task =
            new PaymentEventTasks(3, RETRY_DELAY).processPaymentEventTask(confirmation, refund, events);

    private ExecutionComplete failureAfter(String instanceId, int previousFailures) {
        Execution execution = new Execution(
                START,
                new TaskInstance<Void>(PaymentEventTasks.PROCESS, instanceId),
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
    @DisplayName("SEC-031: grava o GAVE_UP e só depois remove a tarefa")
    void shouldRecordTheGiveUpBeforeRemovingTheTask() {
        UUID id = UUID.randomUUID();
        ExecutionComplete complete = failureAfter(id.toString(), 2);

        task.getFailureHandler().onFailure(complete, ops);

        InOrder order = inOrder(confirmation, ops);
        order.verify(confirmation).giveUp(id);
        order.verify(ops).remove();
    }

    @Test
    @DisplayName("SEC-031: se o GAVE_UP não puder ser gravado, a tarefa fica e é reagendada")
    void shouldKeepTheTaskWhenTheGiveUpCannotBeRecorded() {
        UUID id = UUID.randomUUID();
        ExecutionComplete complete = failureAfter(id.toString(), 2);
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(confirmation)
                .giveUp(id);

        FailureHandler<Void> handler = task.getFailureHandler();
        assertThatCode(() -> handler.onFailure(complete, ops)).doesNotThrowAnyException();

        verify(ops).reschedule(complete, DONE.plus(RETRY_DELAY));
        verify(ops, never()).remove();
    }

    @Test
    @DisplayName("SEC-031: antes da última tentativa só reagenda")
    void shouldRetryBeforeTheLastAttempt() {
        UUID id = UUID.randomUUID();
        ExecutionComplete complete = failureAfter(id.toString(), 1);

        task.getFailureHandler().onFailure(complete, ops);

        verify(ops).reschedule(complete, DONE.plus(RETRY_DELAY));
        verify(confirmation, never()).giveUp(any());
        verify(ops, never()).remove();
    }

    @Test
    @DisplayName("SEC-031: tarefa com id que não é UUID é removida na última falha, sem tocar no pagamento")
    void shouldRemoveATaskWhoseInstanceIdIsNotAUuid() {
        ExecutionComplete complete = failureAfter("not-a-uuid", 2);

        task.getFailureHandler().onFailure(complete, ops);

        verify(ops).remove();
        verifyNoInteractions(confirmation);
    }

    @Test
    @DisplayName("SEC-031: tarefa com id que não é UUID não executa nada")
    void shouldNotRunATaskWhoseInstanceIdIsNotAUuid() {
        TaskInstance<Void> instance = new TaskInstance<>(PaymentEventTasks.PROCESS, "not-a-uuid");

        assertThatCode(() -> task.execute(instance, mock(ExecutionContext.class)))
                .doesNotThrowAnyException();

        verifyNoInteractions(events, confirmation, refund);
    }
}
