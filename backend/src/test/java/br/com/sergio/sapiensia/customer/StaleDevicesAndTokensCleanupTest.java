package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StaleDevicesAndTokensCleanupTest {

    @Autowired
    private KnownDevices knownDevices;

    @Autowired
    private PasswordResetTokens passwordResetTokens;

    @Autowired
    private EmailConfirmationTokens emailConfirmationTokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("#64: apaga só o aparelho sem uso há mais de 365 dias")
    void shouldDeleteOnlyDevicesNotSeenForMoreThan365Days() {
        UUID customerId = insertCustomer();
        String oldHash = insertDevice(customerId, "366 days");
        String recentHash = insertDevice(customerId, "364 days");

        int deleted = knownDevices.deleteNotSeenWithinCookieMaxAge();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(countDevices(customerId, oldHash)).isZero();
        assertThat(countDevices(customerId, recentHash)).isEqualTo(1);
    }

    @Test
    @DisplayName("#61: apaga o link de nova senha usado e o vencido, e guarda o válido sem uso")
    void shouldDeleteUsedAndExpiredPasswordResetLinks() {
        UUID customerId = insertCustomer();
        String usedHash = insertResetToken(customerId, "now() + interval '30 minutes'", "now()");
        String expiredHash = insertResetToken(customerId, "now() - interval '1 minute'", "null");
        String validHash = insertResetToken(customerId, "now() + interval '30 minutes'", "null");

        int deleted = passwordResetTokens.deleteUsedOrExpired();

        assertThat(deleted).isGreaterThanOrEqualTo(2);
        assertThat(countResetTokens(usedHash)).isZero();
        assertThat(countResetTokens(expiredHash)).isZero();
        assertThat(countResetTokens(validHash)).isEqualTo(1);
    }

    @Test
    @DisplayName("#51: apaga só o link de confirmação de e-mail vencido")
    void shouldDeleteOnlyExpiredConfirmationLinks() {
        UUID customerId = insertCustomer();
        String expiredHash = insertConfirmationToken(customerId, "now() - interval '1 minute'");
        String validHash = insertConfirmationToken(customerId, "now() + interval '1 hour'");

        int deleted = emailConfirmationTokens.deleteExpired();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(countConfirmationTokens(expiredHash)).isZero();
        assertThat(countConfirmationTokens(validHash)).isEqualTo(1);
    }

    @Test
    @DisplayName("#64: a limpeza não espera um aparelho travado por um login: pula e apaga na vez seguinte")
    void shouldSkipADeviceLockedByALogin() throws Exception {
        UUID customerId = insertCustomer();
        String hash = insertDevice(customerId, "400 days");

        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement(
                    "select 1 from customer_devices where customer_id = ? and token_hash = ? for update")) {
                statement.setObject(1, customerId);
                statement.setString(2, hash);
                statement.executeQuery().close();
            }

            // run off the test thread so a wait on the lock fails the test in 2 s instead of hanging it
            CompletableFuture<Integer> cleanup =
                    CompletableFuture.supplyAsync(() -> knownDevices.deleteNotSeenWithinCookieMaxAge());
            cleanup.get(2, TimeUnit.SECONDS);

            assertThat(countDevices(customerId, hash)).isEqualTo(1);
            lock.rollback();
        }

        knownDevices.deleteNotSeenWithinCookieMaxAge();

        assertThat(countDevices(customerId, hash)).isZero();
    }

    @Test
    void shouldRegisterTheDailyCleanupTaskInTheScheduler() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        int rows = 0;
        while (System.nanoTime() < deadline) {
            rows = jdbc.queryForObject(
                    "select count(*) from scheduled_tasks where task_name = 'delete-stale-devices-and-tokens'",
                    Integer.class);
            if (rows > 0) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(rows).isEqualTo(1);
    }

    private UUID insertCustomer() {
        return jdbc.queryForObject(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, 'hash', now())"
                        + " returning id",
                UUID.class,
                "stale-" + UUID.randomUUID() + "@example.com");
    }

    private String insertDevice(UUID customerId, String age) {
        String hash = randomHash();
        jdbc.update(
                "insert into customer_devices (customer_id, token_hash, last_seen_at)"
                        + " values (?, ?, now() - ?::interval)",
                customerId,
                hash,
                age);
        return hash;
    }

    private String insertResetToken(UUID customerId, String expiresAtSql, String usedAtSql) {
        String hash = randomHash();
        jdbc.update(
                "insert into password_reset_tokens (customer_id, token_hash, expires_at, used_at)" + " values (?, ?, "
                        + expiresAtSql + ", " + usedAtSql + ")",
                customerId,
                hash);
        return hash;
    }

    private String insertConfirmationToken(UUID customerId, String expiresAtSql) {
        String hash = randomHash();
        jdbc.update(
                "insert into email_confirmation_tokens (customer_id, token_hash, expires_at)" + " values (?, ?, "
                        + expiresAtSql + ")",
                customerId,
                hash);
        return hash;
    }

    private String randomHash() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private int countDevices(UUID customerId, String hash) {
        return jdbc.queryForObject(
                "select count(*) from customer_devices where customer_id = ? and token_hash = ?",
                Integer.class,
                customerId,
                hash);
    }

    private int countResetTokens(String hash) {
        return jdbc.queryForObject(
                "select count(*) from password_reset_tokens where token_hash = ?", Integer.class, hash);
    }

    private int countConfirmationTokens(String hash) {
        return jdbc.queryForObject(
                "select count(*) from email_confirmation_tokens where token_hash = ?", Integer.class, hash);
    }
}
