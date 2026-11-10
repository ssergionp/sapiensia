package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorage;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorageConfig;
import br.com.sergio.sapiensia.product.TestPdfs;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
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

/**
 * SEC-010, SEC-011, SEC-017: who may upload the PDF of a Product, by state of the session. A denied answer is the same
 * whether the Product exists or not, and the storage is never touched by a denied request.
 */
@Import({
    TestcontainersConfiguration.class,
    AdminMfaTestSupport.ClockConfig.class,
    SwitchableProductFileStorageConfig.class
})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=admin-product-file-authorization",
            "app.login-limit.ip-max-attempts=1000",
            "app.storage.file.dir=target/test-storage-file-authorization"
        })
class AdminProductFileAuthorizationTest {

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

    @Autowired
    SwitchableProductFileStorage storage;

    private AdminProductFixtures.Admin account;
    private String customerEmail;
    private UUID existing;

    @BeforeEach
    void setUp() {
        clearState();
        account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        customerEmail = "cliente-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, ?, now())",
                customerEmail,
                passwordEncoder.encode(PASSWORD));
        existing = AdminProductFixtures.insertProduct(jdbc);
    }

    @AfterEach
    void tearDown() {
        clearState();
    }

    private void clearState() {
        for (String key : storage.keys()) {
            storage.delete(key);
        }
        jdbc.update("delete from product_files");
        jdbc.update("delete from products");
    }

    private static String path(UUID id) {
        return PRODUCTS + "/" + id + "/file";
    }

    private static UploadBodies.Upload validUpload() {
        return UploadBodies.of("guia.pdf", "application/pdf", TestPdfs.pages(1));
    }

    private int fileRows() {
        return jdbc.queryForObject("select count(*) from product_files", Integer.class);
    }

    private void assertNothingStored() {
        assertThat(storage.keys()).isEmpty();
        assertThat(fileRows()).isZero();
    }

    private AdminBrowser fullAdmin() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
        return browser;
    }

    /** The two ids a denied state must answer alike: a Product that exists and one that does not. */
    private void assertDenied(AdminBrowser browser, int status, String state) throws Exception {
        HttpResponse<String> forExisting = browser.postMultipart(path(existing), validUpload(), true);
        HttpResponse<String> forMissing = browser.postMultipart(path(UUID.randomUUID()), validUpload(), true);
        assertThat(forExisting.statusCode()).as(state).isEqualTo(status);
        assertThat(forMissing.statusCode()).as(state).isEqualTo(status);
        assertThat(forMissing.body().replaceAll("\"(timestamp|path)\":\"[^\"]*\"", ""))
                .as(state)
                .isEqualTo(forExisting.body().replaceAll("\"(timestamp|path)\":\"[^\"]*\"", ""));
        // the default error body repeats the path asked; apart from that it must not carry the id
        assertThat(forExisting.body().replaceAll("\"path\":\"[^\"]*\"", "")).doesNotContain(existing.toString());
    }

    @Test
    @DisplayName("SEC-010 SEC-011: anônimo recebe 401 e Cliente recebe 403, igual para ID existente e inexistente")
    void shouldDenyAnonymousAndCustomer() throws Exception {
        assertDenied(new AdminBrowser(port), 401, "anonymous");
        AdminBrowser customer = new AdminBrowser(port);
        assertThat(customer.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);
        assertDenied(customer, 403, "customer");
        assertNothingStored();
    }

    @Test
    @DisplayName("SEC-010 SEC-001: sessão pendente de MFA (só a senha) e pendente vencida recebem 401")
    void shouldDenyMfaPendingSessions() throws Exception {
        AdminBrowser pending = new AdminBrowser(port);
        assertThat(pending.loginAdmin(account.email(), PASSWORD).statusCode()).isEqualTo(204);
        assertDenied(pending, 401, "password only");

        clock.advance(Duration.ofMinutes(10));
        assertDenied(pending, 401, "expired pending");
        assertNothingStored();
    }

    @Test
    @DisplayName("SEC-017: envio sem o token CSRF dá 403 e nada é gravado")
    void shouldRefuseAnUploadWithoutCsrfToken() throws Exception {
        AdminBrowser admin = fullAdmin();

        HttpResponse<String> response = admin.postMultipart(path(existing), validUpload(), false);

        assertThat(response.statusCode()).isEqualTo(403);
        assertNothingStored();
    }

    @Test
    @DisplayName("SEC-011: com a capacidade, ID inexistente dá 404 e nada é gravado")
    void shouldAnswer404AndStoreNothingForAnUnknownProduct() throws Exception {
        AdminBrowser admin = fullAdmin();

        HttpResponse<String> response = admin.postMultipart(path(UUID.randomUUID()), validUpload(), true);

        assertThat(response.statusCode()).isEqualTo(404);
        assertNothingStored();
    }

    @Test
    @DisplayName("SEC-011: envio para o Produto A não toca a linha nem o objeto do Produto B")
    void shouldNotTouchAnotherProductsFile() throws Exception {
        UUID other = AdminProductFixtures.insertProduct(jdbc);
        AdminBrowser admin = fullAdmin();
        assertThat(admin.postMultipart(path(other), validUpload(), true).statusCode())
                .isEqualTo(200);
        var rowOfB = jdbc.queryForMap("select * from product_files where product_id = ?", other);
        Set<String> keysBefore = storage.keys();
        assertThat(keysBefore).hasSize(1);

        assertThat(admin.postMultipart(path(existing), validUpload(), true).statusCode())
                .isEqualTo(200);

        assertThat(jdbc.queryForMap("select * from product_files where product_id = ?", other))
                .isEqualTo(rowOfB);
        assertThat(storage.keys()).containsAll(keysBefore).hasSize(2);
        assertThat(fileRows()).isEqualTo(2);
    }
}
