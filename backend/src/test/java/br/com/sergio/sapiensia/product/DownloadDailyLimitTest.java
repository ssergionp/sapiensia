package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * SEC-015: at most 10 links per Customer and Product per day (Sao Paulo), refused before signing, and the check is
 * serialized by the Access row lock. Own Customers and Products, isolated context, nothing deleted (download_records
 * is insert-only).
 */
@Import({TestcontainersConfiguration.class, SwitchableProductFileStorageConfig.class})
@SpringBootTest(
        properties = {
            "test.isolated-context=download-daily-limit",
            "app.storage.file.dir=target/test-storage-download-limit"
        })
class DownloadDailyLimitTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ProductDownloads downloads;

    @Autowired
    SwitchableProductFileStorage storage;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    DataSource dataSource;

    @Autowired
    ApplicationContext context;

    private UUID customer;
    private UUID product;

    @BeforeEach
    void setUp() throws Exception {
        customer = newCustomer();
        product = newProductWithAccess(customer);
    }

    @AfterEach
    void resetStorage() {
        storage.failSigns(false);
        storage.holdSigns(null);
    }

    private UUID newCustomer() {
        return jdbc.queryForObject(
                "insert into customers (email, password_hash, email_confirmed_at)"
                        + " values (?, 'x', now()) returning id",
                UUID.class,
                "cliente-" + UUID.randomUUID() + "@example.com");
    }

    private UUID newProductWithAccess(UUID forCustomer) throws Exception {
        UUID created = jdbc.queryForObject(
                "insert into products (title, description, category, price_cents, status, first_published_at)"
                        + " values ('Guia de Contratos', 'd', 'LAW', 1000, 'PUBLISHED', now()) returning id",
                UUID.class);
        LocalDownloadFixtures.storeProductFile(context, jdbc, created, "%PDF-1.7 x".getBytes());
        grantAccess(forCustomer, created);
        return created;
    }

    private void grantAccess(UUID forCustomer, UUID forProduct) {
        UUID order = UUID.randomUUID();
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at, paid_at)"
                        + " values (?, ?, ?, 1000, 'PAID', ?, now(), now() + interval '30 minutes', now())",
                order,
                forCustomer,
                forProduct,
                "pay_" + UUID.randomUUID());
        jdbc.update(
                "insert into accesses (customer_id, product_id, order_id, granted_at) values (?, ?, ?, now())",
                forCustomer,
                forProduct,
                order);
    }

    private ProductDownloadService serviceAt(String instant) {
        Instant at = OffsetDateTime.parse(instant).toInstant();
        return new ProductDownloadService(downloads, storage, Clock.fixed(at, ZoneOffset.UTC), transactionManager);
    }

    private long records(UUID forCustomer, UUID forProduct) {
        return jdbc.queryForObject(
                "select count(*) from download_records where customer_id = ? and product_id = ?",
                Long.class,
                forCustomer,
                forProduct);
    }

    private void issueTimes(ProductDownloadService service, UUID forCustomer, UUID forProduct, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(service.issue(forCustomer, forProduct)).isPresent();
        }
    }

    @Test
    @DisplayName("SEC-015: o 11º link do dia é recusado antes de assinar e sem gravar Registro")
    void shouldRefuseTheEleventhLinkBeforeSigning() {
        ProductDownloadService service = serviceAt("2026-11-14T10:00:00-03:00");
        issueTimes(service, customer, product, 10);
        storage.failSigns(true);

        assertThatThrownBy(() -> service.issue(customer, product)).isInstanceOf(DownloadLimitReachedException.class);

        assertThat(records(customer, product)).isEqualTo(10);
    }

    @Test
    @DisplayName("SEC-015: o dia é o de Brasília: zera à 00:00 de São Paulo, não à 00:00 UTC")
    void shouldResetAtMidnightInSaoPauloNotUtc() {
        issueTimes(serviceAt("2026-11-14T20:59:59-03:00"), customer, product, 10);

        assertThatThrownBy(() -> serviceAt("2026-11-14T21:00:00-03:00").issue(customer, product))
                .isInstanceOf(DownloadLimitReachedException.class);
        assertThatThrownBy(() -> serviceAt("2026-11-14T23:59:59-03:00").issue(customer, product))
                .isInstanceOf(DownloadLimitReachedException.class);
        assertThat(serviceAt("2026-11-15T00:00:00-03:00").issue(customer, product))
                .isPresent();

        assertThat(records(customer, product)).isEqualTo(11);
    }

    @Test
    @DisplayName("SEC-015: a contagem é separada por Cliente e por Produto")
    void shouldCountPerCustomerAndPerProduct() throws Exception {
        ProductDownloadService service = serviceAt("2026-11-14T10:00:00-03:00");
        issueTimes(service, customer, product, 10);
        UUID otherProduct = newProductWithAccess(customer);
        UUID otherCustomer = newCustomer();
        grantAccess(otherCustomer, product);

        assertThat(service.issue(customer, otherProduct)).isPresent();
        assertThat(service.issue(otherCustomer, product)).isPresent();
    }

    @Test
    @DisplayName("SEC-015: dois pedidos ao mesmo tempo no 10º geram um link só")
    void shouldIssueOnlyOneLinkForTwoConcurrentTenthRequests() throws Exception {
        ProductDownloadService service = serviceAt("2026-11-14T10:00:00-03:00");
        issueTimes(service, customer, product, 9);
        int baseline = storage.signCalls();
        CountDownLatch release = new CountDownLatch(1);
        storage.holdSigns(release);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<String>> first = pool.submit(() -> service.issue(customer, product));
            awaitSignCalls(baseline + 1);
            Future<Optional<String>> second = pool.submit(() -> service.issue(customer, product));
            awaitBlockedOnAccessLock();
            assertThat(storage.signCalls()).isEqualTo(baseline + 1);
            release.countDown();

            int issued = 0;
            int refused = 0;
            for (Future<Optional<String>> result : java.util.List.of(first, second)) {
                try {
                    assertThat(result.get(10, TimeUnit.SECONDS)).isPresent();
                    issued++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(DownloadLimitReachedException.class);
                    refused++;
                }
            }
            assertThat(issued).isEqualTo(1);
            assertThat(refused).isEqualTo(1);
            assertThat(records(customer, product)).isEqualTo(10);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("SEC-011: Acesso cancelado com o limite batido continua 404, não 429")
    void shouldAnswerNotFoundNotLimitWhenTheAccessIsCancelled() {
        ProductDownloadService service = serviceAt("2026-11-14T10:00:00-03:00");
        issueTimes(service, customer, product, 10);
        jdbc.update(
                "update accesses set cancelled_at = now() where customer_id = ? and product_id = ?", customer, product);

        assertThat(service.issue(customer, product)).isEmpty();
    }

    @Test
    @DisplayName("SEC-015: Storage lento que desiste não grava Registro, devolve a conexão ao pool e solta a trava")
    void shouldReleaseTheConnectionAndTheLockWhenASlowStorageGivesUp() throws Exception {
        ProductDownloadService service = serviceAt("2026-11-14T10:00:00-03:00");
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        int baseConnections = pool.getActiveConnections();
        long recordsBefore = records(customer, product);
        int baseline = storage.signCalls();
        CountDownLatch release = new CountDownLatch(1);
        storage.failSigns(true);
        storage.holdSigns(release);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<String>> slow = executor.submit(() -> service.issue(customer, product));
            awaitSignCalls(baseline + 1);
            assertThat(pool.getActiveConnections()).isEqualTo(baseConnections + 1);
            release.countDown();

            assertThatThrownBy(() -> slow.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ProductFileStorageException.class);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pool.getActiveConnections() > baseConnections && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(pool.getActiveConnections()).isEqualTo(baseConnections);
            assertThat(records(customer, product)).isEqualTo(recordsBefore);

            storage.failSigns(false);
            Future<Optional<String>> again = executor.submit(() -> service.issue(customer, product));
            assertThat(again.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(records(customer, product)).isEqualTo(recordsBefore + 1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void awaitSignCalls(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (storage.signCalls() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(storage.signCalls()).isEqualTo(expected);
    }

    private void awaitBlockedOnAccessLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
                            + " and query like '%for update of a%'",
                    Long.class);
            if (waiting >= 1) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(waiting).isGreaterThanOrEqualTo(1);
    }
}
