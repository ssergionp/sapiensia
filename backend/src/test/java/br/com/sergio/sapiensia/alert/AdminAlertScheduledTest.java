package br.com.sergio.sapiensia.alert;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.email.EmailMessage;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** ADR-0005: the recurring db-scheduler task sends the alert on its own; nobody calls the dispatch here. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        properties = {
            "test.isolated-context=admin-alert-scheduled",
            "app.alerts.dispatch-interval=PT1S",
            "db-scheduler.polling-interval=PT0.2S"
        })
class AdminAlertScheduledTest {

    @Autowired
    AdminAlerts alerts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecordingEmailSender sender;

    @Test
    @DisplayName("ADR-0005: a tarefa recorrente admin-alert-dispatch envia o alerta sozinha")
    void shouldSendThePendingAlertWithoutAnyoneCallingTheDispatch() throws Exception {
        jdbc.update("delete from admin_alerts");
        jdbc.update("delete from administrators");
        String admin = "admin-" + UUID.randomUUID() + "@example.com";
        jdbc.update("insert into administrators (email, password_hash) values (?, 'x')", admin);

        alerts.recordSafely(AdminAlertType.TASK_GAVE_UP, "some-task");

        EmailMessage mail = sender.awaitMessageTo(admin, Duration.ofSeconds(15))
                .orElseThrow(() -> new AssertionError("no alert e-mail arrived within 15 s"));
        assertThat(mail.subject()).startsWith("Alerta SapiensIA: ");
        assertThat(jdbc.queryForObject(
                        "select count(*) from admin_alerts where email_sent_at is not null", Integer.class))
                .isEqualTo(1);
    }
}
