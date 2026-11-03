package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UnconfirmedCustomersCleanupTest {

    private static final String PASSWORD = "uma senha longa 1";

    @Autowired
    private CustomerService customerService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecordingEmailSender emailSender;

    @Test
    @DisplayName("#49(c): apaga conta não confirmada com mais de 7 dias, com os links dela")
    void shouldDeleteUnconfirmedCustomerOlderThanRetentionWithItsTokens() {
        String oldEmail = uniqueEmail();
        String recentEmail = uniqueEmail();
        UUID oldId = insertCustomer(oldEmail, "8 days", false);
        UUID recentId = insertCustomer(recentEmail, "6 days", false);
        insertToken(oldId);
        insertToken(recentId);

        int deleted = customerService.deleteUnconfirmedOlderThanRetention();

        // other tests may leave old unconfirmed rows, so only the rows of this test are checked
        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(countCustomers(oldEmail)).isZero();
        assertThat(countTokens(oldId)).isZero();
        assertThat(countCustomers(recentEmail)).isEqualTo(1);
        assertThat(countTokens(recentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("#49(c): quem se cadastra de novo ganha mais 7 dias, mesmo que a conta já estivesse vencida")
    void shouldKeepUnconfirmedCustomerThatRegisteredAgainBeforeTheCleanup() {
        String email = uniqueEmail();
        UUID id = insertCustomer(email, "8 days", false);

        customerService.register(email, PASSWORD);
        customerService.deleteUnconfirmedOlderThanRetention();

        assertThat(countCustomers(email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select id from customers where email = ?", UUID.class, email))
                .isEqualTo(id);
    }

    @Test
    @DisplayName("#49(c): nunca apaga conta confirmada, por mais antiga que seja")
    void shouldNeverDeleteConfirmedCustomer() {
        String email = uniqueEmail();
        insertCustomer(email, "30 days", true);

        customerService.deleteUnconfirmedOlderThanRetention();

        assertThat(countCustomers(email)).isEqualTo(1);
    }

    @Test
    void shouldRegisterTheDailyCleanupTaskInTheScheduler() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        int rows = 0;
        while (System.nanoTime() < deadline) {
            rows = jdbc.queryForObject(
                    "select count(*) from scheduled_tasks where task_name = 'delete-unconfirmed-customers'",
                    Integer.class);
            if (rows > 0) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void shouldAllowTheSameEmailToRegisterAgainAfterCleanup() throws InterruptedException {
        String email = uniqueEmail();
        UUID oldId = insertCustomer(email, "8 days", false);

        customerService.deleteUnconfirmedOlderThanRetention();
        customerService.register(email, PASSWORD);

        UUID newId = jdbc.queryForObject("select id from customers where email = ?", UUID.class, email);
        assertThat(newId).isNotEqualTo(oldId);
        assertThat(emailSender.awaitMessageTo(email, Duration.ofSeconds(15))).isPresent();
    }

    private String uniqueEmail() {
        return "cleanup-" + UUID.randomUUID() + "@example.com";
    }

    private UUID insertCustomer(String email, String age, boolean confirmed) {
        return jdbc.queryForObject(
                "insert into customers (email, password_hash, created_at, email_confirmed_at) values (?, 'hash',"
                        + " now() - ?::interval, " + (confirmed ? "now()" : "null") + ") returning id",
                UUID.class,
                email,
                age);
    }

    private void insertToken(UUID customerId) {
        jdbc.update(
                "insert into email_confirmation_tokens (customer_id, token_hash, expires_at)"
                        + " values (?, ?, now() + interval '1 day')",
                customerId,
                UUID.randomUUID().toString().replace("-", ""));
    }

    private int countCustomers(String email) {
        return jdbc.queryForObject("select count(*) from customers where email = ?", Integer.class, email);
    }

    private int countTokens(UUID customerId) {
        return jdbc.queryForObject(
                "select count(*) from email_confirmation_tokens where customer_id = ?", Integer.class, customerId);
    }
}
