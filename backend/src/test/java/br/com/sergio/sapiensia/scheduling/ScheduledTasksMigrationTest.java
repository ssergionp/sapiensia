package br.com.sergio.sapiensia.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ScheduledTasksMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("NFR-004: a migration cria a tabela do db-scheduler")
    void shouldCreateSchedulerTableWithOfficialColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name = 'scheduled_tasks'",
                String.class);
        List<String> indexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where tablename = 'scheduled_tasks'", String.class);

        assertThat(columns)
                .containsExactlyInAnyOrder(
                        "task_name",
                        "task_instance",
                        "task_data",
                        "execution_time",
                        "picked",
                        "picked_by",
                        "last_success",
                        "last_failure",
                        "consecutive_failures",
                        "last_heartbeat",
                        "version",
                        "priority");
        assertThat(indexes).contains("execution_time_idx", "last_heartbeat_idx", "priority_execution_time_idx");
    }
}
