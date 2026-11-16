package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-036: an e-mail still queued when the account is anonymized is never sent, to anyone. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"test.isolated-context=anonymization-email", "db-scheduler.polling-interval=PT0.2S"})
class CustomerAnonymizationEmailTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private CustomerAnonymization anonymization;

    @Autowired
    private SchedulerClient scheduler;

    @Autowired
    private RecordingEmailSender sender;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID insertCustomer(String email, boolean confirmed) {
        return jdbc.queryForObject(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, 'x', "
                        + (confirmed ? "now()" : "null") + ") returning id",
                UUID.class,
                email);
    }

    @Test
    @DisplayName(
            "FR-036: e-mail pendente de confirmação, de novo aparelho e de pagamento para conta anonimizada não sai")
    void shouldSendNoPendingEmailToAnAnonymizedAccount() throws Exception {
        String unconfirmedEmail = "pend-" + UUID.randomUUID() + "@example.com";
        String deviceEmail = "pend-" + UUID.randomUUID() + "@example.com";
        String paidEmail = "pend-" + UUID.randomUUID() + "@example.com";
        UUID unconfirmed = insertCustomer(unconfirmedEmail, false);
        UUID device = insertCustomer(deviceEmail, true);
        UUID paid = insertCustomer(paidEmail, true);
        UUID productId = jdbc.queryForObject(
                "insert into products (title, description, category, price_cents, status, first_published_at)"
                        + " values ('Guia', 'd', 'LAW', 4990, 'PUBLISHED', now()) returning id",
                UUID.class);
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at, paid_at)"
                        + " values (?, ?, ?, 4990, 'PAID', ?, now(), now() + interval '30 minutes', now())",
                orderId,
                paid,
                productId,
                "pay_" + UUID.randomUUID());

        Instant runAt = Instant.now().plusSeconds(10);
        String confirmationInstance = UUID.randomUUID().toString();
        String deviceInstance = UUID.randomUUID().toString();
        scheduler.schedule(
                new TaskInstance<>(CustomerEmailTasks.CONFIRMATION, confirmationInstance, unconfirmed), runAt);
        scheduler.schedule(
                new TaskInstance<>(
                        CustomerEmailTasks.NEW_DEVICE_NOTICE, deviceInstance, new NewDeviceNotice(device, runAt)),
                runAt);
        scheduler.schedule(new TaskInstance<Void>("order-paid-email", orderId.toString()), runAt);

        UUID admin = UUID.randomUUID();
        anonymization.anonymize(unconfirmedEmail, admin, "203.0.113.9");
        anonymization.anonymize(deviceEmail, admin, "203.0.113.9");
        anonymization.anonymize(paidEmail, admin, "203.0.113.9");
        assertThat(Instant.now()).as("anonymized before the tasks run").isBefore(runAt);

        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (queued(orderId, confirmationInstance, deviceInstance) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }

        assertThat(queued(orderId, confirmationInstance, deviceInstance))
                .as("tasks ended without retries")
                .isZero();
        List<String> addresses = List.of(unconfirmedEmail, deviceEmail, paidEmail);
        for (String address : addresses) {
            assertThat(sender.attemptsTo(address)).as(address).isZero();
        }
        for (UUID id : List.of(unconfirmed, device, paid)) {
            assertThat(sender.attemptsTo(markerOf(id))).as("marker of " + id).isZero();
        }
    }

    /** The placeholder e-mail the account carries after the anonymization. */
    private String markerOf(UUID customerId) {
        String marker = jdbc.queryForObject("select email from customers where id = ?", String.class, customerId);
        assertThat(marker).endsWith("@anonimizado.invalid");
        return marker;
    }

    private long queued(UUID orderId, String confirmationInstance, String deviceInstance) {
        return jdbc.queryForObject(
                "select count(*) from scheduled_tasks where task_instance in (?, ?, ?)",
                Long.class,
                confirmationInstance,
                deviceInstance,
                orderId.toString());
    }
}
