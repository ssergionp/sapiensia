package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.product.LocalDownloadFixtures;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorageConfig;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-034, SEC-015: a request that waits too long for the Access row lock gives up with the 503 instead of holding a
 * database connection. Own Customer and Product, isolated context, nothing deleted (download_records is insert-only).
 */
@Import({TestcontainersConfiguration.class, SwitchableProductFileStorageConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=download-lock-timeout",
            "app.login-limit.ip-max-attempts=1000",
            "app.storage.file.dir=target/test-storage-download-lock-timeout"
        })
class DownloadLockTimeoutTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    ApplicationContext context;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("FR-034, SEC-015: pedido que espera a trava do Acesso por mais de 3 s recebe 503 e não grava Registro")
    void shouldAnswer503AndWriteNoRecordWhenTheAccessLockIsHeldTooLong() throws Exception {
        OrderHttp.Customer owner = OrderHttp.newCustomer(port, jdbc, encoder);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia de Contratos", 2990);
        LocalDownloadFixtures.storeProductFile(context, jdbc, product, "%PDF-1.7 x".getBytes());
        UUID order = UUID.randomUUID();
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at, paid_at)"
                        + " values (?, ?, ?, 1000, 'PAID', ?, now(), now() + interval '30 minutes', now())",
                order,
                owner.id,
                product,
                "pay_" + UUID.randomUUID());
        jdbc.update(
                "insert into accesses (customer_id, product_id, order_id, granted_at) values (?, ?, ?, now())",
                owner.id,
                product,
                order);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding =
                    holder.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbc.queryForList(
                                "select 1 from accesses where customer_id = ? and product_id = ? for update",
                                owner.id,
                                product);
                        locked.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            HttpResponse<String> answer = owner.post("/api/library/" + product + "/download", "");
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            release.countDown();
            holding.get(10, TimeUnit.SECONDS);
            assertThat(answer.statusCode()).isEqualTo(503);
            assertThat(answer.body()).isEmpty();
            assertThat(waitedMillis).isBetween(2_500L, 6_000L);
            assertThat(jdbc.queryForObject(
                            "select count(*) from download_records where customer_id = ? and product_id = ?",
                            Long.class,
                            owner.id,
                            product))
                    .isZero();

            assertThat(owner.post("/api/library/" + product + "/download", "").statusCode())
                    .isEqualTo(200);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }
}
