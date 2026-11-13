package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorage;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorageConfig;
import br.com.sergio.sapiensia.product.TestImages;
import br.com.sergio.sapiensia.security.SecretCipher;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.List;
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
 * FR-016, SEC-010, SEC-017: the public cover route against the real server, database and (disk) storage, as a Visitor
 * and as a logged-in Customer. The cover is stored through the panel route, so what the Visitor gets is what the whole
 * path produced.
 */
@Import({
    TestcontainersConfiguration.class,
    AdminMfaTestSupport.ClockConfig.class,
    SwitchableProductFileStorageConfig.class
})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=catalog-cover",
            "app.login-limit.ip-max-attempts=1000",
            "app.storage.file.dir=target/test-storage-catalog-cover"
        })
class CatalogCoverTest {

    /** The policy as it was before #21: a copy on purpose, so a change of the constant fails this test (G3). */
    private static final String CSP = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; "
            + "connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
            + "frame-ancestors 'none'";

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
    private AdminBrowser admin;
    private String customerEmail;

    @BeforeEach
    void setUp() throws Exception {
        clearState();
        account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        customerEmail = "cliente-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, ?, now())",
                customerEmail,
                passwordEncoder.encode(PASSWORD));
        admin = new AdminBrowser(port);
        assertThat(admin.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
    }

    @AfterEach
    void tearDown() {
        storage.failGets(false);
        clearState();
    }

    private void clearState() {
        for (String key : storage.keys()) {
            storage.delete(key);
        }
        jdbc.update("delete from pending_object_deletions");
        jdbc.update("delete from product_covers");
        jdbc.update("delete from product_files");
        jdbc.update("delete from products");
    }

    private UUID publishedProduct() {
        UUID id = AdminProductFixtures.insertProductWithFile(jdbc);
        AdminProductFixtures.publish(jdbc, id);
        return id;
    }

    private void uploadPng(UUID id) throws Exception {
        assertThat(uploadCover(id, "c.png", "image/png", TestImages.png(8, 8)).statusCode())
                .isEqualTo(200);
    }

    private HttpResponse<String> uploadCover(UUID id, String filename, String contentType, byte[] bytes)
            throws Exception {
        return admin.postMultipart(PRODUCTS + "/" + id + "/cover", UploadBodies.of(filename, contentType, bytes), true);
    }

    private String version(UUID id) {
        return jdbc.queryForObject("select version from product_covers where product_id = ?", String.class, id);
    }

    private static String publicPath(Object id) {
        return "/api/products/" + id + "/cover";
    }

    private static Optional<String> header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name);
    }

    @Test
    @DisplayName("FR-016 SEC-017: a Visitor gets the cleaned cover of a published Product with the checked type")
    void shouldServeTheCoverWithTheCheckedTypeAndNoCookie() throws Exception {
        UUID id = publishedProduct();
        // the sender claims a GIF named x.svg: only the checked format counts
        assertThat(uploadCover(id, "x.svg", "image/gif", TestImages.png(20, 10)).statusCode())
                .isEqualTo(200);
        AdminBrowser visitor = new AdminBrowser(port);

        HttpResponse<byte[]> response = visitor.getBytes(publicPath(id));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(header(response, "Content-Type")).contains("image/png");
        assertThat(header(response, "X-Content-Type-Options")).contains("nosniff");
        assertThat(header(response, "Cache-Control")).contains("private, no-cache");
        assertThat(header(response, "ETag")).contains("\"" + version(id) + "\"");
        assertThat(header(response, "Content-Length")).contains(String.valueOf(response.body().length));
        assertThat(response.body()).startsWith(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(header(response, "Content-Security-Policy")).contains(CSP);
    }

    @Test
    @DisplayName("SEC-017: If-None-Match with the current ETag is a 304 without body; an unpublished Product is a 404")
    void shouldAnswer304ForTheSameEtagAndNever304ForAnUnpublishedProduct() throws Exception {
        UUID id = publishedProduct();
        uploadPng(id);
        String etag = "\"" + version(id) + "\"";
        AdminBrowser visitor = new AdminBrowser(port);

        HttpResponse<byte[]> notModified = visitor.getBytes(publicPath(id), "If-None-Match", etag);
        HttpResponse<byte[]> other = visitor.getBytes(publicPath(id), "If-None-Match", "\"0000000000000000\"");
        jdbc.update("update products set status = 'UNPUBLISHED' where id = ?", id);
        HttpResponse<byte[]> afterUnpublish = visitor.getBytes(publicPath(id), "If-None-Match", etag);

        assertThat(notModified.statusCode()).isEqualTo(304);
        assertThat(notModified.body()).isEmpty();
        assertThat(header(notModified, "ETag")).contains(etag);
        assertThat(header(notModified, "Cache-Control")).contains("private, no-cache");
        assertThat(other.statusCode()).isEqualTo(200);
        assertThat(afterUnpublish.statusCode()).isEqualTo(404);
        assertThat(afterUnpublish.body()).isEmpty();
    }

    @Test
    @DisplayName("SEC-010: draft, unpublished, missing and cover-less Products get the same empty 404; a bad id is 400")
    void shouldAnswerTheSameEmptyNotFound() throws Exception {
        UUID draft = AdminProductFixtures.insertProduct(jdbc);
        uploadPng(draft);
        UUID unpublished = publishedProduct();
        uploadPng(unpublished);
        jdbc.update("update products set status = 'UNPUBLISHED' where id = ?", unpublished);
        UUID withoutCover = publishedProduct();
        List<UUID> ids = List.of(draft, unpublished, UUID.randomUUID(), withoutCover);
        AdminBrowser visitor = new AdminBrowser(port);
        AdminBrowser customer = new AdminBrowser(port);
        assertThat(customer.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);

        HttpResponse<byte[]> reference = visitor.getBytes(publicPath(draft));
        for (AdminBrowser who : List.of(visitor, customer)) {
            for (UUID id : ids) {
                HttpResponse<byte[]> response = who.getBytes(publicPath(id));

                assertThat(response.statusCode()).as(id.toString()).isEqualTo(404);
                assertThat(response.body()).isEmpty();
                assertThat(header(response, "Content-Type")).isEqualTo(header(reference, "Content-Type"));
                assertThat(header(response, "Content-Length")).isEqualTo(header(reference, "Content-Length"));
                assertThat(header(response, "Cache-Control")).isEqualTo(header(reference, "Cache-Control"));
                assertThat(header(response, "Content-Security-Policy")).contains(CSP);
            }
        }
        assertThat(visitor.getBytes(publicPath("abc")).statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName(
            "SEC-010 G1: the public cover route never sets XSRF-TOKEN or a session cookie, not even on 404 and 304")
    void shouldNeverSetACookieOnTheCoverRoute() throws Exception {
        UUID id = publishedProduct();
        uploadPng(id);
        String etag = "\"" + version(id) + "\"";
        AdminBrowser visitor = new AdminBrowser(port);

        List<HttpResponse<byte[]>> responses = List.of(
                visitor.getBytes(publicPath(id)),
                visitor.getBytes(publicPath(id), "If-None-Match", etag),
                visitor.getBytes(publicPath(UUID.randomUUID())),
                visitor.getBytes(publicPath("abc")));

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(200, 304, 404, 400);
        for (HttpResponse<byte[]> response : responses) {
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }
        assertThat(visitor.cookie("XSRF-TOKEN")).isNull();
        assertThat(visitor.cookie("JSESSIONID")).isNull();
    }

    @Test
    @DisplayName("SEC-010 G3: the Content-Security-Policy of the cover answers is exactly the one in force before")
    void shouldKeepTheContentSecurityPolicyOnEveryCoverAnswer() throws Exception {
        UUID id = publishedProduct();
        uploadPng(id);
        AdminBrowser visitor = new AdminBrowser(port);

        for (HttpResponse<byte[]> response : List.of(
                visitor.getBytes(publicPath(id)),
                visitor.getBytes(publicPath(id), "If-None-Match", "\"" + version(id) + "\""),
                visitor.getBytes(publicPath(UUID.randomUUID())))) {
            assertThat(header(response, "Content-Security-Policy")).contains(CSP);
        }
    }

    @Test
    @DisplayName("SEC-010: only GET is open on the public cover route")
    void shouldDenyEveryOtherVerbOnThePublicRoute() throws Exception {
        UUID id = publishedProduct();
        uploadPng(id);
        String before = version(id);
        AdminBrowser visitor = new AdminBrowser(port);
        AdminBrowser customer = new AdminBrowser(port);
        assertThat(customer.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);

        for (AdminBrowser who : List.of(visitor, customer)) {
            assertThat(who.put(publicPath(id), "{}").statusCode()).isIn(401, 403);
            assertThat(who.post(publicPath(id), "{}").statusCode()).isIn(401, 403);
            assertThat(who.delete(publicPath(id)).statusCode()).isIn(401, 403);
        }
        assertThat(version(id)).isEqualTo(before);
    }

    @Test
    @DisplayName("FR-016: the Catalog list and detail carry coverVersion, null without a cover")
    void shouldCarryTheCoverVersionInTheCatalog() throws Exception {
        UUID with = publishedProduct();
        UUID without = publishedProduct();
        uploadPng(with);
        AdminBrowser visitor = new AdminBrowser(port);

        HttpResponse<String> list = visitor.get("/api/products");
        HttpResponse<String> detailWith = visitor.get("/api/products/" + with);
        HttpResponse<String> detailWithout = visitor.get("/api/products/" + without);

        List<String> versions = JsonPath.read(list.body(), "$.items[?(@.id=='" + with + "')].coverVersion");
        assertThat(versions).containsExactly(version(with));
        assertThat(list.body()).contains("\"coverVersion\":null");
        assertThat((String) JsonPath.read(detailWith.body(), "$.coverVersion")).isEqualTo(version(with));
        assertThat(detailWithout.body()).contains("\"coverVersion\":null");
        assertThat(list.body()).doesNotContain("covers/");
    }

    @Test
    @DisplayName("FR-016: a missing object in the Storage is an empty 404; a Storage outage is a 503 without body")
    void shouldAnswerNotFoundForAMissingObjectAndUnavailableForAnOutage() throws Exception {
        UUID id = publishedProduct();
        uploadPng(id);
        AdminBrowser visitor = new AdminBrowser(port);

        storage.failGets(true);
        HttpResponse<byte[]> down = visitor.getBytes(publicPath(id));
        storage.failGets(false);
        for (String key : storage.keys()) {
            storage.delete(key);
        }
        HttpResponse<byte[]> missing = visitor.getBytes(publicPath(id));

        assertThat(down.statusCode()).isEqualTo(503);
        assertThat(down.body()).isEmpty();
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).isEmpty();
    }
}
