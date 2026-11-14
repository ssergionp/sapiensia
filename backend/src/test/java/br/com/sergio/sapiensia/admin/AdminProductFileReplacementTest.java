package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.audit.SwitchableAuditLog;
import br.com.sergio.sapiensia.audit.SwitchableAuditLogConfig;
import br.com.sergio.sapiensia.product.LocalDownloadFixtures;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorage;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorageConfig;
import br.com.sergio.sapiensia.product.TestPdfs;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.io.IOException;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Replacing the file of a Product that was ever published needs a recent authentication (SEC-008, ADR-0004): the lock
 * is a security rule that runs before the body is read, and the same rule is re-checked under the row lock. A Product
 * that was never published stays as in #18. "Published" is always reached through the #19 routes, never through SQL.
 */
@Import({
    TestcontainersConfiguration.class,
    AdminMfaTestSupport.ClockConfig.class,
    SwitchableProductFileStorageConfig.class,
    SwitchableAuditLogConfig.class
})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=admin-product-file-replacement",
            "app.login-limit.ip-max-attempts=1000",
            "app.storage.file.dir=target/test-storage-file-replacement"
        })
class AdminProductFileReplacementTest {

    private static final long LIMIT = 50L * 1024 * 1024;
    private static final Duration OUT_OF_WINDOW = Duration.ofMinutes(5).plusSeconds(1);
    private static final String REAUTHENTICATION = "/api/admin/sessions/reauthentication";
    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    @Autowired
    org.springframework.context.ApplicationContext context;

    @Autowired
    SwitchableAuditLog auditLog;

    private AdminProductFixtures.Admin account;
    private UUID productId;
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
        productId = AdminProductFixtures.insertProduct(jdbc);
        admin = fullSession();
    }

    @AfterEach
    void tearDown() {
        clearState();
    }

    private void clearState() {
        storage.failPuts(false);
        storage.failDeletes(false);
        storage.resumePuts();
        auditLog.failWrites(false);
        for (String key : storage.keys()) {
            storage.delete(key);
        }
        jdbc.update("delete from pending_object_deletions");
        jdbc.update("delete from product_files");
        jdbc.update("delete from products");
        if (customerEmail != null) {
            jdbc.update("delete from customers where email = ?", customerEmail);
        }
    }

    private AdminBrowser fullSession() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
        return browser;
    }

    private static String path(UUID id) {
        return PRODUCTS + "/" + id + "/file";
    }

    private static UploadBodies.Upload validUpload() {
        return UploadBodies.of("guia.pdf", "application/pdf", TestPdfs.pages(1));
    }

    private HttpResponse<String> upload(AdminBrowser browser, UUID id) throws Exception {
        return browser.postMultipart(path(id), validUpload(), true);
    }

    private HttpResponse<String> reauthenticate(AdminBrowser browser) throws Exception {
        clock.advance(Duration.ofSeconds(30));
        String code = AdminMfaTestSupport.codeAt(account.secret(), clock.instant());
        return browser.post(REAUTHENTICATION, "{\"password\":\"" + PASSWORD + "\",\"code\":\"" + code + "\"}");
    }

    /** A Product with its first file, published and (optionally) unpublished again, all through the #19 routes. */
    private UUID publishedProduct(AdminBrowser browser, boolean unpublish) throws Exception {
        UUID id = AdminProductFixtures.insertProduct(jdbc);
        assertThat(upload(browser, id).statusCode()).isEqualTo(200);
        assertThat(browser.put(PRODUCTS + "/" + id + "/publication", "").statusCode())
                .isEqualTo(200);
        if (unpublish) {
            assertThat(browser.delete(PRODUCTS + "/" + id + "/publication").statusCode())
                    .isEqualTo(200);
        }
        return id;
    }

    private String currentKey(UUID id) {
        return jdbc.queryForObject("select object_key from product_files where product_id = ?", String.class, id);
    }

    private long blocked(UUID product) {
        return jdbc.queryForObject(
                "select count(*) from audit_events where action = 'ADMIN_PRODUCT_FILE_UPLOADED'"
                        + " and result = 'BLOCKED' and actor_id = ? and object_type = 'PRODUCT' and object_id = ?",
                Long.class,
                account.id(),
                product);
    }

    private static void assertReauthenticationRequired(HttpResponse<String> response, UUID product) throws Exception {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(JSON.readTree(response.body()))
                .isEqualTo(JSON.readTree("{\"type\":\"about:blank\",\"title\":\"Reauthentication required\","
                        + "\"status\":403,\"instance\":\"/api/admin/products/" + product + "/file\","
                        + "\"errors\":{\"reauthentication\":\"REAUTHENTICATION_REQUIRED\"}}"));
    }

    /** The plain 403 carries the time and the path of the request: everything else must be the same. */
    private static tools.jackson.databind.JsonNode withoutTimestampAndPath(HttpResponse<String> response) {
        tools.jackson.databind.node.ObjectNode body =
                (tools.jackson.databind.node.ObjectNode) JSON.readTree(response.body());
        body.remove("timestamp");
        body.remove("path");
        return body;
    }

    private static List<Path> ourTempFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> p.getFileName().toString().startsWith("product-file-"))
                    .toList();
        }
    }

    private static long biggestTomcatTempFile() throws IOException {
        long biggest = 0;
        try (Stream<Path> top = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            for (Path dir : top.filter(p -> p.getFileName().toString().startsWith("tomcat."))
                    .toList()) {
                try (Stream<Path> files = Files.walk(dir)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        biggest = Math.max(biggest, Files.size(file));
                    }
                }
            }
        }
        return biggest;
    }

    @Test
    @DisplayName("ADR-0004: rascunho nunca publicado troca o arquivo sem reautenticação")
    void shouldReplaceTheFileOfADraftWithoutReauthentication() throws Exception {
        assertThat(upload(admin, productId).statusCode()).isEqualTo(200);
        String first = currentKey(productId);
        clock.advance(OUT_OF_WINDOW);

        HttpResponse<String> response = upload(admin, productId);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(currentKey(productId)).isNotEqualTo(first);
        assertThat(storage.keys()).containsExactlyInAnyOrder(first, currentKey(productId));
        assertThat(blocked(productId)).isZero();
    }

    @Test
    @DisplayName("SEC-008 ADR-0004: Produto publicado e despublicado recusa a troca sem reautenticação recente")
    void shouldRefuseThePublishedAndUnpublishedProductWithoutRecentAuthentication() throws Exception {
        UUID id = publishedProduct(admin, true);
        String before = currentKey(id);
        Set<String> objects = storage.keys();
        clock.advance(OUT_OF_WINDOW);

        HttpResponse<String> response = upload(admin, id);

        assertReauthenticationRequired(response, id);
        assertThat(currentKey(id)).isEqualTo(before);
        assertThat(storage.keys()).isEqualTo(objects);
        assertThat(blocked(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-008: a recusa vem antes de ler o corpo")
    void shouldRefuseBeforeReadingTheBody() throws Exception {
        UUID id = publishedProduct(admin, false);
        String before = currentKey(id);
        clock.advance(OUT_OF_WINDOW);
        List<Path> tempBefore = ourTempFiles();

        // only the headers go out: an answer proves the server decided without the body
        String request = "POST " + path(id) + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                + "Cookie: JSESSIONID=" + admin.cookie("JSESSIONID") + "; XSRF-TOKEN=" + admin.cookie("XSRF-TOKEN")
                + "\r\nX-XSRF-TOKEN: " + admin.cookie("XSRF-TOKEN") + "\r\n"
                + "Content-Type: multipart/form-data; boundary=x\r\nContent-Length: " + LIMIT
                + "\r\nConnection: close\r\n\r\n--x\r\n";
        String statusLine;
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            statusLine = new String(socket.getInputStream().readNBytes(12), StandardCharsets.ISO_8859_1);
        }
        assertThat(statusLine).isEqualTo("HTTP/1.1 403");

        // and a real 50 MB stream is not consumed: the server may close the connection before the client finishes
        AtomicLong sent = new AtomicLong();
        try {
            HttpResponse<String> response = admin.postMultipart(
                    path(id),
                    UploadBodies.stream("file", "guia.pdf", "application/pdf", UploadBodies.filler(LIMIT, sent), LIMIT),
                    true);
            assertThat(response.statusCode()).isEqualTo(403);
        } catch (IOException closed) {
            // the connection was closed while the client was still sending: also a refusal before the body
        }

        assertThat(sent.get()).as("the body was not consumed").isLessThan(LIMIT);
        assertThat(ourTempFiles()).isEqualTo(tempBefore);
        assertThat(biggestTomcatTempFile()).isLessThan(LIMIT);
        assertThat(currentKey(id)).isEqualTo(before);
        assertThat(blocked(id)).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName(
            "SEC-008 FR-010: depois de reautenticar, a troca vale e o objeto anterior fica no Storage até a limpeza")
    void shouldReplaceTheFileAfterReauthenticationAndDropTheOldObject() throws Exception {
        UUID id = publishedProduct(admin, false);
        String oldKey = currentKey(id);
        clock.advance(OUT_OF_WINDOW);
        assertThat(reauthenticate(admin).statusCode()).isEqualTo(204);

        HttpResponse<String> response = upload(admin, id);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String newKey = currentKey(id);
        assertThat(newKey).isNotEqualTo(oldKey);
        assertThat(jdbc.queryForObject("select count(*) from product_files where product_id = ?", Long.class, id))
                .isEqualTo(1);
        assertThat(storage.keys()).containsExactlyInAnyOrder(oldKey, newKey);
        assertThat(jdbc.queryForList("select object_key from pending_object_deletions", String.class))
                .containsExactly(oldKey);
        jdbc.update("update pending_object_deletions set created_at = now() - interval '16 minutes'");
        LocalDownloadFixtures.runObjectCleanup(context);
        assertThat(storage.keys()).containsExactly(newKey);
        assertThat(blocked(id)).isZero();
    }

    @Test
    @DisplayName("SEC-008 SEC-017: depois da reautenticação o envio usa a sessão e o token CSRF novos")
    void shouldUseTheNewSessionAndCsrfTokenAfterReauthentication() throws Exception {
        UUID id = publishedProduct(admin, false);
        String before = currentKey(id);
        Set<String> objects = storage.keys();
        clock.advance(OUT_OF_WINDOW);
        String oldSession = admin.cookie("JSESSIONID");
        String oldCsrf = admin.cookie("XSRF-TOKEN");

        assertThat(reauthenticate(admin).statusCode()).isEqualTo(204);

        String newSession = admin.cookie("JSESSIONID");
        String newCsrf = admin.cookie("XSRF-TOKEN");
        assertThat(newSession).isNotEqualTo(oldSession);
        assertThat(newCsrf).isNotEqualTo(oldCsrf);
        // new session, old token: a CSRF refusal, not the answer of the reauthentication rule
        HttpResponse<String> staleToken = admin.postMultipartWithToken(path(id), validUpload(), oldCsrf);
        assertThat(staleToken.statusCode()).isEqualTo(403);
        assertThat(staleToken.body()).doesNotContain("REAUTHENTICATION_REQUIRED");
        // old session, new token: the old session ended with the reauthentication
        AdminBrowser replay = new AdminBrowser(port);
        replay.setCookie("JSESSIONID", oldSession);
        replay.setCookie("XSRF-TOKEN", newCsrf);
        assertThat(replay.postMultipart(path(id), validUpload(), true).statusCode())
                .isEqualTo(401);
        assertThat(currentKey(id)).isEqualTo(before);
        assertThat(storage.keys()).isEqualTo(objects);

        assertThat(admin.postMultipart(path(id), validUpload(), true).statusCode())
                .isEqualTo(200);
        assertThat(currentKey(id)).isNotEqualTo(before);
    }

    @Test
    @DisplayName(
            "SEC-008 FR-010: outra sessão sem reautenticação recente é recusada e a tentativa vai para a auditoria")
    void shouldRefuseAnotherSessionWithoutRecentAuthenticationAndAuditIt() throws Exception {
        UUID id = publishedProduct(admin, false);
        AdminBrowser sessionB = admin;
        clock.advance(OUT_OF_WINDOW);
        AdminBrowser sessionA = fullSession();
        assertThat(reauthenticate(sessionA).statusCode()).isEqualTo(204);
        String before = currentKey(id);
        Set<String> objects = storage.keys();

        HttpResponse<String> response = upload(sessionB, id);

        assertReauthenticationRequired(response, id);
        assertThat(currentKey(id)).isEqualTo(before);
        assertThat(storage.keys()).isEqualTo(objects);
        assertThat(blocked(id)).isEqualTo(1);
        // the stamp lives in each session: A, which reauthenticated, is not refused
        assertThat(upload(sessionA, id).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-008 FR-010: sessão esquecida aberta, com a janela vencida, não troca o arquivo")
    void shouldRefuseAForgottenSessionOnceTheWindowIsOver() throws Exception {
        UUID id = publishedProduct(admin, false);
        clock.advance(OUT_OF_WINDOW);
        assertThat(reauthenticate(admin).statusCode()).isEqualTo(204);
        clock.advance(Duration.ofMinutes(4));
        assertThat(upload(admin, id).statusCode()).isEqualTo(200);
        String before = currentKey(id);
        Set<String> objects = storage.keys();
        clock.advance(OUT_OF_WINDOW);

        HttpResponse<String> response = upload(admin, id);

        assertReauthenticationRequired(response, id);
        assertThat(currentKey(id)).isEqualTo(before);
        assertThat(storage.keys()).isEqualTo(objects);
        assertThat(blocked(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-008: janela que vence durante o upload não recusa o envio já autorizado")
    void shouldNotRefuseAnAuthorizedUploadWhenTheWindowExpiresDuringIt() throws Exception {
        UUID id = publishedProduct(admin, false);
        String before = currentKey(id);
        clock.advance(Duration.ofMinutes(4));
        SwitchableProductFileStorage.Pause pause = storage.pausePuts();
        CompletableFuture<HttpResponse<String>> slow = CompletableFuture.supplyAsync(() -> {
            try {
                return upload(admin, id);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            assertThat(pause.awaitReached()).as("the upload was authorized").isTrue();
            clock.advance(Duration.ofMinutes(2));
        } finally {
            pause.release();
        }

        HttpResponse<String> response = slow.get(30, TimeUnit.SECONDS);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(currentKey(id)).isNotEqualTo(before);
        assertThat(blocked(id)).isZero();
    }

    @Test
    @DisplayName(
            "SEC-011: Cliente recebe 403 igual para id existente e inexistente; Administrador 404 para inexistente")
    void shouldAnswerTheSameToACustomerForAnyIdAndNotFoundToTheAdministrator() throws Exception {
        UUID published = publishedProduct(admin, false);
        UUID draft = productId;
        clock.advance(OUT_OF_WINDOW);
        AdminBrowser customer = new AdminBrowser(port);
        assertThat(customer.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);
        Set<String> objects = storage.keys();

        HttpResponse<String> forPublished = upload(customer, published);
        HttpResponse<String> forDraft = upload(customer, draft);
        HttpResponse<String> forMissing = upload(customer, UUID.randomUUID());

        assertThat(forPublished.statusCode()).isEqualTo(403);
        assertThat(forDraft.statusCode()).isEqualTo(403);
        assertThat(forMissing.statusCode()).isEqualTo(403);
        assertThat(withoutTimestampAndPath(forPublished)).isEqualTo(withoutTimestampAndPath(forMissing));
        assertThat(withoutTimestampAndPath(forDraft)).isEqualTo(withoutTimestampAndPath(forMissing));
        assertThat(forMissing.body()).doesNotContain("REAUTHENTICATION_REQUIRED");
        assertThat(upload(admin, UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertReauthenticationRequired(upload(admin, published), published);
        assertThat(upload(admin, draft).statusCode()).isEqualTo(200);
        assertThat(storage.keys()).hasSize(objects.size() + 1);
    }

    @Test
    @DisplayName("SEC-011: reautenticar e trocar o arquivo do Produto A não toca o B")
    void shouldNotTouchProductBWhenReplacingTheFileOfProductA() throws Exception {
        UUID a = publishedProduct(admin, false);
        UUID b = publishedProduct(admin, false);
        String keyB = currentKey(b);
        List<java.util.Map<String, Object>> rowB =
                jdbc.queryForList("select * from product_files where product_id = ?", b);
        clock.advance(OUT_OF_WINDOW);
        assertThat(reauthenticate(admin).statusCode()).isEqualTo(204);

        assertThat(upload(admin, a).statusCode()).isEqualTo(200);

        assertThat(jdbc.queryForList("select * from product_files where product_id = ?", b))
                .isEqualTo(rowB);
        assertThat(storage.keys()).contains(keyB).hasSize(3); // B, plus A current and A replaced (pending cleanup)
    }
}
