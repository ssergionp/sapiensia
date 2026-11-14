package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorage;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorageConfig;
import br.com.sergio.sapiensia.product.TestImages;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.net.http.HttpResponse;
import java.util.Optional;
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
 * FR-029, FR-025, SEC-011: the cover of a Product in the Library of the Customer who has an active Access, whether or
 * not the Product is still published. Covers are stored through the panel route, like in {@link CatalogCoverTest}.
 */
@Import({
    TestcontainersConfiguration.class,
    AdminMfaTestSupport.ClockConfig.class,
    SwitchableProductFileStorageConfig.class
})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=library-cover",
            "app.login-limit.ip-max-attempts=1000",
            "app.storage.file.dir=target/test-storage-library-cover"
        })
class LibraryCoverTest {

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

    private AdminBrowser admin;
    private UUID ownerId;
    private AdminBrowser owner;
    private AdminBrowser other;

    @BeforeEach
    void setUp() throws Exception {
        clearState();
        AdminProductFixtures.Admin account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        admin = new AdminBrowser(port);
        assertThat(admin.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
        ownerId = insertCustomer();
        owner = loginAs(ownerId);
        other = loginAs(insertCustomer());
    }

    @AfterEach
    void tearDown() {
        clearState();
    }

    private void clearState() {
        for (String key : storage.keys()) {
            storage.delete(key);
        }
        jdbc.update("delete from accesses");
        jdbc.update("delete from orders");
        jdbc.update("delete from pending_object_deletions");
        jdbc.update("delete from product_covers");
        jdbc.update("delete from product_files");
        jdbc.update("delete from products");
    }

    private UUID insertCustomer() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at) values (?, ?, ?, now())",
                id,
                "cliente-" + id + "@example.com",
                passwordEncoder.encode(PASSWORD));
        return id;
    }

    private AdminBrowser loginAs(UUID customerId) throws Exception {
        String email = jdbc.queryForObject("select email from customers where id = ?", String.class, customerId);
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginCustomer(email, PASSWORD).statusCode()).isEqualTo(204);
        return browser;
    }

    private UUID productWithCover(boolean published) throws Exception {
        UUID id = AdminProductFixtures.insertProductWithFile(jdbc);
        if (published) {
            AdminProductFixtures.publish(jdbc, id);
        }
        HttpResponse<String> upload = admin.postMultipart(
                PRODUCTS + "/" + id + "/cover", UploadBodies.of("c.png", "image/png", TestImages.png(8, 8)), true);
        assertThat(upload.statusCode()).isEqualTo(200);
        return id;
    }

    private void grant(UUID customerId, UUID productId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at, paid_at)"
                        + " values (?, ?, ?, 1000, 'PAID', ?, now(), now() + interval '30 minutes', now())",
                orderId,
                customerId,
                productId,
                "pay_" + UUID.randomUUID());
        jdbc.update(
                "insert into accesses (customer_id, product_id, order_id, granted_at) values (?, ?, ?, now())",
                customerId,
                productId,
                orderId);
    }

    private String etag(UUID productId) {
        return "\""
                + jdbc.queryForObject(
                        "select version from product_covers where product_id = ?", String.class, productId)
                + "\"";
    }

    private static String path(Object id) {
        return "/api/library/" + id + "/cover";
    }

    private static Optional<String> header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name);
    }

    @Test
    @DisplayName("FR-029: shouldServeTheCoverOfAnUnpublishedProductToItsOwner")
    void shouldServeTheCoverOfAnUnpublishedProductToItsOwner() throws Exception {
        UUID id = productWithCover(true);
        grant(ownerId, id);
        jdbc.update("update products set status = 'UNPUBLISHED' where id = ?", id);

        HttpResponse<byte[]> response = owner.getBytes(path(id));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(header(response, "Content-Type")).contains("image/png");
        assertThat(header(response, "X-Content-Type-Options")).contains("nosniff");
        assertThat(header(response, "Cache-Control")).contains("private, no-cache");
        assertThat(header(response, "ETag")).contains(etag(id));
        assertThat(response.body()).startsWith(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
    }

    @Test
    @DisplayName("FR-029: shouldAnswerNotModifiedWhenTheVersionMatches")
    void shouldAnswerNotModifiedWhenTheVersionMatches() throws Exception {
        UUID id = productWithCover(false);
        grant(ownerId, id);

        HttpResponse<byte[]> response = owner.getBytes(path(id), "If-None-Match", etag(id));

        assertThat(response.statusCode()).isEqualTo(304);
        assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("SEC-011: shouldAnswerNotFoundToAnotherCustomer")
    void shouldAnswerNotFoundToAnotherCustomer() throws Exception {
        UUID id = productWithCover(true);
        grant(ownerId, id);

        HttpResponse<byte[]> response = other.getBytes(path(id), "If-None-Match", etag(id));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("FR-025: shouldAnswerNotFoundForACancelledAccess")
    void shouldAnswerNotFoundForACancelledAccess() throws Exception {
        UUID id = productWithCover(true);
        grant(ownerId, id);
        jdbc.update("update accesses set cancelled_at = now() where product_id = ?", id);

        HttpResponse<byte[]> plain = owner.getBytes(path(id));
        HttpResponse<byte[]> conditional = owner.getBytes(path(id), "If-None-Match", etag(id));

        assertThat(plain.statusCode()).isEqualTo(404);
        assertThat(plain.body()).isEmpty();
        assertThat(conditional.statusCode()).isEqualTo(404);
        assertThat(conditional.body()).isEmpty();
    }

    @Test
    @DisplayName("FR-029: shouldAnswerNotFoundWithoutCover")
    void shouldAnswerNotFoundWithoutCover() throws Exception {
        UUID id = AdminProductFixtures.insertProductWithFile(jdbc);
        grant(ownerId, id);

        assertThat(owner.getBytes(path(id)).statusCode()).isEqualTo(404);
        assertThat(owner.getBytes(path(UUID.randomUUID())).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("FR-029: shouldAnswerBadRequestForAMalformedId")
    void shouldAnswerBadRequestForAMalformedId() throws Exception {
        // the same answer as the public route (CatalogCoverTest): 400 for an id that is not a UUID
        assertThat(owner.getBytes(path("abc")).statusCode()).isEqualTo(400);
    }
}
