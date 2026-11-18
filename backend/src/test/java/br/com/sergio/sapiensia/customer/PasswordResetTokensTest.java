package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PasswordResetTokensTest {

    @Autowired
    private PasswordResetTokens tokens;

    @Autowired
    private KnownDevices knownDevices;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID customerId;

    @BeforeEach
    void createCustomer() {
        customerId = UUID.randomUUID();
        jdbc.update(
                "insert into customers (id, email, password_hash) values (?, ?, 'x')",
                customerId,
                "reset-tokens-" + customerId + "@example.com");
    }

    private long rows() {
        return jdbc.queryForObject(
                "select count(*) from password_reset_tokens where customer_id = ?", Long.class, customerId);
    }

    @Test
    @DisplayName("SEC-007: o token tem 32 bytes aleatórios e o banco guarda só o hash")
    void shouldIssueRandomTokensAndStoreOnlyTheirHash() {
        String first = tokens.issue(customerId);
        String second = tokens.issue(customerId);

        assertThat(first).isNotEqualTo(second).matches("[A-Za-z0-9_-]{43}");
        assertThat(second).matches("[A-Za-z0-9_-]{43}");
        List<String> hashes = jdbc.queryForList(
                "select token_hash from password_reset_tokens where customer_id = ?", String.class, customerId);
        assertThat(hashes).hasSize(2).allSatisfy(hash -> assertThat(hash).matches("[0-9a-f]{64}"));
        String wholeTable = jdbc.queryForObject(
                "select coalesce(string_agg(t::text, ' '), '') from password_reset_tokens t", String.class);
        assertThat(wholeTable).doesNotContain(first).doesNotContain(second);
    }

    @Test
    @DisplayName("SEC-007: o link vale 30 minutos")
    void shouldExpireTheLinkAfterThirtyMinutes() {
        tokens.issue(customerId);

        Long seconds = jdbc.queryForObject(
                "select extract(epoch from expires_at - created_at)::bigint from password_reset_tokens"
                        + " where customer_id = ?",
                Long.class,
                customerId);
        assertThat(seconds).isBetween(1799L, 1801L);
    }

    @Test
    @DisplayName("SEC-007: revokeUnused apaga os links não usados do cliente e preserva os usados e os de outros")
    void shouldRevokeOnlyUnusedLinksOfTheCustomer() {
        UUID other = UUID.randomUUID();
        jdbc.update(
                "insert into customers (id, email, password_hash) values (?, ?, 'x')",
                other,
                "reset-other-" + other + "@example.com");
        tokens.issue(customerId);
        tokens.issue(customerId);
        tokens.issue(other);
        tokens.issue(customerId);
        jdbc.update(
                "update password_reset_tokens set used_at = now() where id = (select id from password_reset_tokens"
                        + " where customer_id = ? limit 1)",
                customerId);

        tokens.revokeUnused(customerId);

        assertThat(rows()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from password_reset_tokens where customer_id = ? and used_at is not null",
                        Long.class,
                        customerId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from password_reset_tokens where customer_id = ?", Long.class, other))
                .isEqualTo(1);
    }

    private void addDevice(UUID owner) {
        jdbc.update(
                "insert into customer_devices (customer_id, token_hash) values (?, ?)",
                owner,
                EmailConfirmationTokens.hash(PasswordResetTokens.newToken()));
    }

    private long devices(UUID owner) {
        return jdbc.queryForObject("select count(*) from customer_devices where customer_id = ?", Long.class, owner);
    }

    @Test
    @DisplayName("SEC-007: consumir o link apaga os aparelhos da conta, na mesma transação, e só os dela")
    void shouldForgetTheDevicesOfTheAccountWhenTheLinkIsConsumed() {
        UUID other = UUID.randomUUID();
        jdbc.update(
                "insert into customers (id, email, password_hash) values (?, ?, 'x')",
                other,
                "reset-devices-other-" + other + "@example.com");
        addDevice(customerId);
        addDevice(customerId);
        addDevice(other);
        String token = tokens.issue(customerId);

        assertThat(tokens.consumeAndSetPassword(token, "new-hash")).contains(customerId);

        assertThat(devices(customerId)).isZero();
        assertThat(devices(other)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-007: um link inválido ou já usado não apaga os aparelhos")
    void shouldKeepTheDevicesWhenTheLinkIsInvalidOrUsed() {
        addDevice(customerId);
        String token = tokens.issue(customerId);
        assertThat(tokens.consumeAndSetPassword(PasswordResetTokens.newToken(), "h"))
                .isEmpty();
        assertThat(devices(customerId)).isEqualTo(1);

        assertThat(tokens.consumeAndSetPassword(token, "h")).isPresent();
        addDevice(customerId);

        assertThat(tokens.consumeAndSetPassword(token, "h")).isEmpty();
        assertThat(devices(customerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-007: um aparelho gravado por um login enquanto a senha trocava também é esquecido")
    void shouldForgetADeviceRegisteredWhileThePasswordWasChanging() throws Exception {
        String token = tokens.issue(customerId);
        CountDownLatch loginRegistered = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            AtomicReference<KnownDevices.DeviceCheck> loginOutcome = new AtomicReference<>();
            Future<?> login =
                    pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        loginOutcome.set(knownDevices.recognize(customerId, 0, null));
                        loginRegistered.countDown();
                        await(releaseLogin);
                    }));
            assertThat(loginRegistered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Optional<UUID>> change = pool.submit(() -> tokens.consumeAndSetPassword(token, "new-hash"));
            try {
                awaitAQueryWaitingOnALock();
            } finally {
                releaseLogin.countDown();
            }
            login.get(10, TimeUnit.SECONDS);
            assertThat(change.get(10, TimeUnit.SECONDS)).contains(customerId);
            assertThat(loginOutcome.get()).isNotNull().isNotEqualTo(KnownDevices.DeviceCheck.NOT_REGISTERED);
        }

        assertThat(jdbc.queryForObject("select session_version from customers where id = ?", Integer.class, customerId))
                .isEqualTo(1);
        assertThat(devices(customerId)).isZero();
    }

    @Test
    @DisplayName("SEC-007: um link já usado não espera nem trava o login da conta")
    void shouldNotWaitForTheCustomerLockWithAUsedLink() throws Exception {
        String token = tokens.issue(customerId);
        assertThat(tokens.consumeAndSetPassword(token, "new-hash")).contains(customerId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> login =
                    pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbc.queryForObject(
                                "select id from customers where id = ? for no key update", UUID.class, customerId);
                        locked.countDown();
                        await(release);
                    }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                Future<Optional<UUID>> reuse = pool.submit(() -> tokens.consumeAndSetPassword(token, "other-hash"));
                assertThat(reuse.get(2, TimeUnit.SECONDS)).isEmpty();
            } finally {
                release.countDown();
            }
            login.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("SEC-007: um link inexistente não apaga os aparelhos da conta")
    void shouldKeepTheDevicesWhenTheLinkIsNotValid() {
        addDevice(customerId);
        tokens.issue(customerId);

        assertThat(tokens.consumeAndSetPassword("token-inexistente", "new-hash"))
                .isEmpty();

        assertThat(devices(customerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-007: a troca de senha não trava em deadlock com um login ou um novo link da mesma conta")
    void shouldNotDeadlockWhenAPasswordChangeMeetsALoginOrANewLink() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            for (int round = 0; round < 20; round++) {
                UUID id = UUID.randomUUID();
                jdbc.update(
                        "insert into customers (id, email, password_hash) values (?, ?, 'x')",
                        id,
                        "reset-deadlock-" + id + "@example.com");
                String token = tokens.issue(id);
                int sessionVersion =
                        jdbc.queryForObject("select session_version from customers where id = ?", Integer.class, id);
                CountDownLatch start = new CountDownLatch(1);
                List<Callable<Object>> work = List.of(
                        () -> tokens.consumeAndSetPassword(token, "new-hash"),
                        () -> knownDevices.recognize(id, sessionVersion, null),
                        () -> tokens.reissue(id));
                List<Future<Object>> results = new ArrayList<>();
                for (Callable<Object> task : work) {
                    results.add(pool.submit(() -> {
                        await(start);
                        return task.call();
                    }));
                }
                start.countDown();
                for (Future<Object> result : results) {
                    try {
                        result.get(10, TimeUnit.SECONDS);
                    } catch (ExecutionException e) {
                        throw new AssertionError(
                                "round " + round + (isDeadlock(e) ? " ended in a deadlock" : " failed unexpectedly"),
                                e);
                    }
                }
            }
        }
    }

    private static boolean isDeadlock(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "40P01".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the latch was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Waits until a query of this test's database is blocked on a lock (the password change behind the login). */
    private void awaitAQueryWaitingOnALock() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (jdbc.queryForObject(
                        "select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
                                + " and datname = current_database()",
                        Long.class)
                == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the password change never waited for the login's lock");
            }
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("SEC-007: pedidos paralelos da mesma conta deixam só um link válido")
    void shouldLeaveOnlyOneUnusedLinkWhenReissuedInParallel() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return tokens.reissue(customerId);
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).matches("[A-Za-z0-9_-]{43}");
            }
        }

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-033: a tabela de links só guarda o necessário")
    void shouldStoreOnlyWhatIsNeeded() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'password_reset_tokens'",
                String.class);

        assertThat(columns)
                .containsExactlyInAnyOrder("id", "customer_id", "token_hash", "expires_at", "used_at", "created_at");
    }

    @Test
    void shouldDeleteTokensWhenCustomerIsDeleted() {
        tokens.issue(customerId);

        jdbc.update("delete from customers where id = ?", customerId);

        assertThat(rows()).isZero();
    }
}
