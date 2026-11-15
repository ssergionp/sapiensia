package br.com.sergio.sapiensia.alert;

import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

/** A fixed-delay schedule whose first run is one interval after startup, not at startup. */
final class FirstRunAfterInterval implements Schedule, Serializable {

    private final Duration interval;
    private final FixedDelay delegate;

    FirstRunAfterInterval(Duration interval) {
        this.interval = interval;
        this.delegate = FixedDelay.of(interval);
    }

    @Override
    public Instant getNextExecutionTime(ExecutionComplete executionComplete) {
        return delegate.getNextExecutionTime(executionComplete);
    }

    /** FixedDelay would run at startup, and a context under test would dispatch once in the background. */
    @Override
    public Instant getInitialExecutionTime(Instant now) {
        return now.plus(interval);
    }

    @Override
    public boolean isDeterministic() {
        return delegate.isDeterministic();
    }
}
