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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
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

    @BeforeEach
    void setUp() throws Exception {
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

                publishing = CompletableFuture.supplyAsync(() -> {
                    try {
                        return admin.put(PRODUCTS + "/" + id + "/publication", "");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });

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
}
