package br.com.sergio.sapiensia.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FirstRunAfterIntervalTest {

    @Test
    @DisplayName("ADR-0005: a tarefa de alertas faz a primeira execução um intervalo depois da partida, não na partida")
    void shouldScheduleTheFirstRunOneIntervalAfterStartup() {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");

        Instant first = AdminAlertTasks.schedule(Duration.ofMinutes(1)).getInitialExecutionTime(now);

        assertThat(first).isEqualTo(now.plus(Duration.ofMinutes(1)));
    }
}
