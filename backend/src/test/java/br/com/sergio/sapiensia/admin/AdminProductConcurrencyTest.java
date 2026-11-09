package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.priceJson;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.updateJson;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** FR-016, SEC-029: the optimistic lock on the version column, against the real server and database. */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-product-concurrency", "app.login-limit.ip-max-attempts=1000"})
class AdminProductConcurrencyTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AdminMfaTestSupport.MutableClock clock;

    private AdminBrowser admin;
    private UUID id;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from products");
        AdminProductFixtures.Admin account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        admin = new AdminBrowser(port);
        assertThat(admin.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
        id = AdminProductFixtures.insertProduct(jdbc);
    }

    private int version() {
        return jdbc.queryForObject("select version from products where id = ?", Integer.class, id);
    }

    /** Runs both calls at the same moment and returns their status codes. */
    private List<Integer> race(Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> a = pool.submit(() -> {
                start.await();
                return first.call().statusCode();
            });
            Future<Integer> b = pool.submit(() -> {
                start.await();
                return second.call().statusCode();
            });
            start.countDown();
            return List.of(a.get(), b.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("FR-016: versão velha no PUT do texto dá 409 e a linha não muda")
    void shouldRefuseAStaleTextEdit() throws Exception {
        assertThat(admin.put(PRODUCTS + "/" + id, updateJson("Primeiro", "d", "LAW", 0))
                        .statusCode())
                .isEqualTo(200);

        HttpResponse<String> stale = admin.put(PRODUCTS + "/" + id, updateJson("Segundo", "d", "OTHER", 0));

        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForMap("select title, category, version from products where id = ?", id))
                .containsEntry("title", "Primeiro")
                .containsEntry("category", "LAW")
                .containsEntry("version", 1);
    }

    @Test
    @DisplayName("SEC-029: versão velha no PUT do preço dá 409 e o preço não muda")
    void shouldRefuseAStalePriceChange() throws Exception {
        assertThat(admin.put(PRODUCTS + "/" + id + "/price", priceJson(5000, 0)).statusCode())
                .isEqualTo(200);

        HttpResponse<String> stale = admin.put(PRODUCTS + "/" + id + "/price", priceJson(9000, 0));

        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select price_cents from products where id = ?", Integer.class, id))
                .isEqualTo(5000);
        assertThat(version()).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-016: dois PUT do texto com a mesma versão ao mesmo tempo: um 200 e um 409")
    void shouldLetOnlyOneParallelTextEditWin() throws Exception {
        List<Integer> statuses = race(
                () -> admin.put(PRODUCTS + "/" + id, updateJson("A", "d", "LAW", 0)),
                () -> admin.put(PRODUCTS + "/" + id, updateJson("B", "d", "LAW", 0)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(version()).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-029: uma edição de texto e uma troca de preço com a mesma versão: um 200 e um 409")
    void shouldLetOnlyOneOfTextEditAndPriceChangeWin() throws Exception {
        List<Integer> statuses = race(
                () -> admin.put(PRODUCTS + "/" + id, updateJson("A", "d", "LAW", 0)),
                () -> admin.put(PRODUCTS + "/" + id + "/price", priceJson(9000, 0)));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(version()).isEqualTo(1);
    }
}
