package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorage;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorageConfig;
import br.com.sergio.sapiensia.product.TestImages;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
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
 * FR-016, SEC-010, SEC-017: the panel routes of the cover. Who may use them, what each refusal looks like and that a
 * refusal leaves the Storage, both tables and the temporary directory exactly as they were (G2).
 */
@Import({
    TestcontainersConfiguration.class,
    AdminMfaTestSupport.ClockConfig.class,
    SwitchableProductFileStorageConfig.class
})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=admin-product-cover",
            "app.login-limit.ip-max-attempts=1000",
            "app.storage.file.dir=target/test-storage-admin-cover"
        })
class AdminProductCoverTest {

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
    private UUID productId;
    private AdminBrowser admin;

    @BeforeEach
    void setUp() throws Exception {
        clearState();
        account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        customerEmail = "cliente-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, ?, now())",
                customerEmail,
                passwordEncoder.encode(PASSWORD));
        productId = AdminProductFixtures.insertProduct(jdbc);
        admin = new AdminBrowser(port);
        assertThat(admin.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
    }

    @AfterEach
    void tearDown() {
        storage.failPuts(false);
        storage.failDeletes(false);
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

    private String path() {
        return PRODUCTS + "/" + productId + "/cover";
    }

    private HttpResponse<String> upload(byte[] bytes) throws Exception {
        return admin.postMultipart(path(), UploadBodies.of("c.png", "image/png", bytes), true);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private static List<Path> ourTempFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("product-cover-") || name.startsWith("cover-");
                    })
                    .toList();
        }
    }

    private void assertNothingChanged(List<Path> tempBefore) throws IOException {
        assertThat(storage.keys()).isEmpty();
        assertThat(count("product_covers")).isZero();
        assertThat(count("pending_object_deletions")).isZero();
        assertThat(ourTempFiles()).isEqualTo(tempBefore);
    }

    @Test
    @DisplayName("FR-016: the Administrator uploads, previews the draft cover, replaces and removes it")
    void shouldUploadPreviewReplaceAndRemove() throws Exception {
        HttpResponse<String> first = upload(TestImages.png(30, 20));

        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(count("product_covers")).isEqualTo(1);
        assertThat(storage.keys()).hasSize(1);
        String firstVersion = jdbc.queryForObject("select version from product_covers", String.class);

        HttpResponse<byte[]> preview = admin.getBytes(path());
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(preview.headers().firstValue("ETag")).contains("\"" + firstVersion + "\"");
        assertThat(preview.headers().firstValue("Cache-Control")).contains("private, no-cache");
        assertThat(admin.getBytes(path(), "If-None-Match", "\"" + firstVersion + "\"")
                        .statusCode())
                .isEqualTo(304);

        assertThat(upload(TestImages.png(31, 21)).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select version from product_covers", String.class))
                .isNotEqualTo(firstVersion);
        assertThat(storage.keys()).hasSize(1);

        HttpResponse<String> removed = admin.delete(path());
        assertThat(removed.statusCode()).isEqualTo(204);
        assertThat(count("product_covers")).isZero();
        assertThat(storage.keys()).isEmpty();
        assertThat(admin.getBytes(path()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("FR-016: a missing Product is 404 on upload, preview and removal")
    void shouldAnswer404ForAMissingProduct() throws Exception {
        String missing = PRODUCTS + "/" + UUID.randomUUID() + "/cover";

        assertThat(admin.postMultipart(missing, UploadBodies.of("c.png", "image/png", TestImages.png(8, 8)), true)
                        .statusCode())
                .isEqualTo(404);
        assertThat(admin.getBytes(missing).statusCode()).isEqualTo(404);
        assertThat(admin.delete(missing).statusCode()).isEqualTo(404);
        assertThat(admin.getBytes(path()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("FR-016: refusals carry {errors:{cover:CODE}}: no part, empty, not an image, too many pixels")
    void shouldAnswerTheCodeOfEachRefusal() throws Exception {
        List<Path> tempBefore = ourTempFiles();

        HttpResponse<String> noPart = admin.postMultipart(
                path(), UploadBodies.ofPart("other", "c.png", "image/png", TestImages.png(8, 8)), true);
        HttpResponse<String> empty = upload(new byte[0]);
        HttpResponse<String> text = upload("<html>not an image</html>".getBytes());
        HttpResponse<String> huge = upload(TestImages.pngHeaderOnly(4001, 10));

        assertThat(noPart.statusCode()).isEqualTo(400);
        assertThat(noPart.body()).contains("\"errors\":{\"cover\":\"COVER_REQUIRED\"}");
        assertThat(empty.statusCode()).isEqualTo(400);
        assertThat(empty.body()).contains("\"errors\":{\"cover\":\"COVER_EMPTY\"}");
        assertThat(text.statusCode()).isEqualTo(400);
        assertThat(text.body()).contains("\"errors\":{\"cover\":\"COVER_NOT_IMAGE\"}");
        assertThat(huge.statusCode()).isEqualTo(400);
        assertThat(huge.body()).contains("\"errors\":{\"cover\":\"COVER_TOO_MANY_PIXELS\"}");
        assertNothingChanged(tempBefore);
    }

    @Test
    @DisplayName("FR-016: a cover over 2 MB is 413 COVER_TOO_LARGE and nothing is stored")
    void shouldAnswer413ForACoverOverTheLimit() throws Exception {
        List<Path> tempBefore = ourTempFiles();
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        Arrays.fill(big, (byte) 'x');

        HttpResponse<String> response = upload(big);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"errors\":{\"cover\":\"COVER_TOO_LARGE\"}");
        assertNothingChanged(tempBefore);
    }

    @Test
    @DisplayName("FR-016: a Storage outage on upload is 503 without body and leaves no row and no temporary file")
    void shouldAnswer503WhenTheStorageIsDown() throws Exception {
        List<Path> tempBefore = ourTempFiles();
        storage.failPuts(true);

        HttpResponse<String> response = upload(TestImages.png(8, 8));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isEmpty();
        assertThat(count("product_covers")).isZero();
        assertThat(storage.keys()).isEmpty();
        assertThat(ourTempFiles()).isEqualTo(tempBefore);
    }

    @Test
    @DisplayName(
            "SEC-017 G2: truncated, lying-length or lying-size files are 400 COVER_INVALID, never 500, nothing stored")
    void shouldFailClosedOnMalformedFiles() throws Exception {
        byte[] png = TestImages.png(40, 40);
        byte[] truncatedPng = Arrays.copyOf(png, png.length / 2);
        byte[] jpeg = TestImages.jpeg(40, 40);
        // an APP1 segment that declares 0xFFFF bytes, far more than the file holds
        byte[] lyingJpeg = TestImages.concat(
                Arrays.copyOfRange(jpeg, 0, 2),
                new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) 0xFF, (byte) 0xFF, 'E', 'x'},
                Arrays.copyOfRange(jpeg, 2, jpeg.length));
        byte[] webp = TestImages.webpLossless1x1();
        // the VP8L chunk declares 0x7FFFFFF0 bytes and the RIFF size is just as wrong
        byte[] lyingWebp = webp.clone();
        System.arraycopy(TestImages.le32(0x7FFFFFF0L), 0, lyingWebp, 4, 4);
        byte[] lyingChunk = webp.clone();
        System.arraycopy(TestImages.le32(0xFFFFFFF0L), 0, lyingChunk, 16, 4);
        List<Path> tempBefore = ourTempFiles();

        for (byte[] bad : List.of(truncatedPng, lyingJpeg, lyingWebp, lyingChunk)) {
            HttpResponse<String> response = upload(bad);

            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
            assertThat(response.body()).contains("\"errors\":{\"cover\":\"COVER_INVALID\"}");
            assertNothingChanged(tempBefore);
        }
    }

    @Test
    @DisplayName(
            "SEC-010 SEC-011: Visitor 401 and Customer 403 on POST, DELETE and GET of the panel route; cover unchanged")
    void shouldDenyVisitorAndCustomerOnThePanelRoutes() throws Exception {
        assertThat(upload(TestImages.png(8, 8)).statusCode()).isEqualTo(200);
        String version = jdbc.queryForObject("select version from product_covers", String.class);
        AdminBrowser customer = new AdminBrowser(port);
        assertThat(customer.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);

        for (var entry :
                List.of(java.util.Map.entry(new AdminBrowser(port), 401), java.util.Map.entry(customer, 403))) {
            AdminBrowser who = entry.getKey();
            int status = entry.getValue();
            assertThat(who.postMultipart(path(), UploadBodies.of("c.png", "image/png", TestImages.png(9, 9)), true)
                            .statusCode())
                    .isEqualTo(status);
            assertThat(who.delete(path()).statusCode()).isEqualTo(status);
            assertThat(who.getBytes(path()).statusCode()).isEqualTo(status);
        }
        assertThat(jdbc.queryForObject("select version from product_covers", String.class))
                .isEqualTo(version);
        assertThat(storage.keys()).hasSize(1);
    }
}
