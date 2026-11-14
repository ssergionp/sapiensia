package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A1: a payment provider that never answers must neither hold database connections nor turn into a 500. The real
 * adapter talks to a local server that holds every answer for 30 s; the read timeout is 1 s and the pool has 2
 * connections.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=order-slow-provider",
            // the scheduler (needed by other beans, so not disabled) must not poll the database while the test counts
            // connections
            "db-scheduler.polling-interval=PT1H",
            "app.pix.provider=asaas",
            "app.asaas.api.read-timeout=PT1S",
            "app.login-limit.ip-max-attempts=1000",
            "spring.datasource.hikari.maximum-pool-size=2",
            "spring.datasource.hikari.connection-timeout=5000"
        })
class OrderSlowProviderTest {

    private static final String CPF = "52998224725";
    private static final ExecutorService SERVER_THREADS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpServer SLOW = startSlowServer();

    private static HttpServer startSlowServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(SERVER_THREADS);
            server.createContext("/", exchange -> {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        registry.add(
                "app.asaas.api.base-url",
                () -> "http://127.0.0.1:" + SLOW.getAddress().getPort());
        // built at run time: a literal that looks like an Asaas key is flagged by gitleaks
        registry.add("app.asaas.api.key", () -> "$aact_" + "hmlg_" + "test" + "0000000000");
    }

    @AfterAll
    static void stopServer() {
        SLOW.stop(0);
        SERVER_THREADS.shutdownNow();
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    DataSource dataSource;

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from orders");
        jdbc.update("delete from payment_customers");
    }

    private record Timed(HttpResponse<String> response, long millis) {}

    private static Timed timed(java.util.concurrent.Callable<HttpResponse<String>> call) {
        try {
            long start = System.nanoTime();
            HttpResponse<String> response = call.call();
            return new Timed(
                    response, Duration.ofNanos(System.nanoTime() - start).toMillis());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int activeConnections() throws Exception {
        return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
    }

    /** The connection of a request that just answered may still be on its way back to the pool: wait up to 5 s. */
    private int activeConnectionsOnceSettled() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        int active = activeConnections();
        while (active != 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
            active = activeConnections();
        }
        return active;
    }

    @Test
    @DisplayName("SEC-018: shouldAnswer502AndFreeThePoolWhenTheProviderIsSlow")
    void shouldAnswer502AndFreeThePoolWhenTheProviderIsSlow() throws Exception {
        UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia de Contratos", 2990);
        List<OrderHttp.Customer> buyers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            buyers.add(OrderHttp.newCustomer(port, jdbc, encoder));
        }

        List<CompletableFuture<Timed>> calls = buyers.stream()
                .map(buyer -> CompletableFuture.supplyAsync(() -> timed(() -> buyer.order(productId, CPF))))
                .toList();

        for (CompletableFuture<Timed> call : calls) {
            Timed answer = call.get();
            assertThat(answer.response().statusCode()).isEqualTo(502);
            assertThat(OrderHttp.json(answer.response(), "$.errors.payment")).isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE");
            assertThat(answer.millis()).isLessThan(5_000);
        }
        assertThat(activeConnectionsOnceSettled()).isZero();
        assertThat(buyers.get(0).get("/api/products").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("SEC-018: shouldAnswer502WithoutHoldingAConnectionWhenTheProviderIsSlowOnView")
    void shouldAnswer502WithoutHoldingAConnectionWhenTheProviderIsSlowOnView() throws Exception {
        UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia de Contratos", 2990);
        OrderHttp.Customer buyer = OrderHttp.newCustomer(port, jdbc, encoder);
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at)"
                        + " values (?, ?, ?, 2990, 'PENDING', ?, now(), now() + interval '30 minutes')",
                orderId,
                buyer.id,
                productId,
                "pay_slow_" + UUID.randomUUID());

        List<CompletableFuture<Timed>> views = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            views.add(CompletableFuture.supplyAsync(() -> timed(() -> buyer.get("/api/orders/" + orderId))));
        }
        // let the four reads reach the slow provider
        Thread.sleep(300);

        Timed catalog = timed(() -> buyer.get("/api/products"));
        // the catalog answered while none of the slow reads had finished: they did not hold the pool
        assertThat(catalog.response().statusCode()).isEqualTo(200);
        assertThat(views).noneMatch(CompletableFuture::isDone);
        assertThat(activeConnectionsOnceSettled()).isZero();

        for (CompletableFuture<Timed> view : views) {
            Timed answer = view.get();
            assertThat(answer.response().statusCode()).isEqualTo(502);
            assertThat(OrderHttp.json(answer.response(), "$.errors.payment")).isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE");
            assertThat(answer.millis()).isLessThan(5_000);
        }
    }
}
