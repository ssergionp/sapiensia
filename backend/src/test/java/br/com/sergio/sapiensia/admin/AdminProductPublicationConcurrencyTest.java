package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** FR-011: publishing shares the Product row lock with the file swap, so it never misses a file being saved. */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=admin-product-publication-concurrency",
            "app.login-limit.ip-max-attempts=1000"
        })
class AdminProductPublicationConcurrencyTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AdminMfaTestSupport.MutableClock clock;

    private AdminBrowser admin;
    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @BeforeEach
    void setUp() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        jdbc.update("delete from product_covers");
        jdbc.update("delete from product_files");
        jdbc.update("delete from products");
        AdminProductFixtures.Admin account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        admin = new AdminBrowser(port);
        assertThat(admin.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
    }

    @Test
    @DisplayName("FR-011: publicar espera a troca de arquivo em andamento e vê o arquivo gravado")
    void shouldWaitForTheFileSwapInProgressAndSeeTheSavedFile() throws Exception {
        UUID id = AdminProductFixtures.insertProduct(jdbc);
        AdminProductFixtures.addCover(jdbc, id);
        CompletableFuture<HttpResponse<String>> publishing;
        try (Connection swap = dataSource.getConnection()) {
            swap.setAutoCommit(false);
            try (PreparedStatement lock = swap.prepareStatement("select id from products where id = ? for update");
                    PreparedStatement insert = swap.prepareStatement("insert into product_files"
                            + " (product_id, object_key, size_bytes, page_count) values (?, ?, 100, 1)")) {
                lock.setObject(1, id);
                lock.executeQuery().close();
                insert.setObject(1, id);
                insert.setString(2, "products/" + id + "/" + UUID.randomUUID() + ".pdf");
                insert.executeUpdate();

                publishing = CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return admin.put(PRODUCTS + "/" + id + "/publication", "");
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        },
                        executor);

                // the file is not committed yet: the request must be parked on the lock, not answering 409 or 500
                assertThatThrownBy(() -> publishing.get(1500, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                swap.commit();
            }
        }

        HttpResponse<String> response = publishing.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("FR-011: publicar e remover a Capa ao mesmo tempo nunca deixam um Produto publicado sem Capa")
    void shouldNeverEndPublishedWithoutACover() throws Exception {
        for (int round = 0; round < 10; round++) {
            UUID id = AdminProductFixtures.insertProductWithFileAndCover(jdbc);
            CompletableFuture<HttpResponse<String>> publishing;
            CompletableFuture<HttpResponse<String>> removing;
            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                try (PreparedStatement lock =
                        holder.prepareStatement("select id from products where id = ? for update")) {
                    lock.setObject(1, id);
                    lock.executeQuery().close();
                    // both requests park on the row lock; they are released together by the commit below
                    publishing = CompletableFuture.supplyAsync(
                            () -> send(() -> admin.put(PRODUCTS + "/" + id + "/publication", "")), executor);
                    removing = CompletableFuture.supplyAsync(
                            () -> send(() -> admin.delete(PRODUCTS + "/" + id + "/cover")), executor);
                    assertThatThrownBy(() -> publishing.get(500, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                    assertThatThrownBy(() -> removing.get(500, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                    holder.commit();
                }
            }

            int published = publishing.get(10, TimeUnit.SECONDS).statusCode();
            int removed = removing.get(10, TimeUnit.SECONDS).statusCode();

            String status = jdbc.queryForObject("select status from products where id = ?", String.class, id);
            long covers =
                    jdbc.queryForObject("select count(*) from product_covers where product_id = ?", Long.class, id);
            if ("PUBLISHED".equals(status)) {
                assertThat(covers)
                        .as("published without a Capa, round " + round)
                        .isEqualTo(1);
            }
            // either the publication won (then the removal is refused) or the removal won (then publishing is refused)
            assertThat(Stream.of(published, removed).filter(code -> code == 409))
                    .as("round %d: publish %d, remove %d", round, published, removed)
                    .hasSize(1);
            // the request that did not get the 409 must have succeeded, not failed with a 5xx
            assertThat(published == 409 ? removed : published)
                    .as("round %d: the winning request", round)
                    .isEqualTo(published == 409 ? 204 : 200);
        }
    }

    private static HttpResponse<String> send(ThrowingRequest request) {
        try {
            return request.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private interface ThrowingRequest {
        HttpResponse<String> run() throws Exception;
    }
}
