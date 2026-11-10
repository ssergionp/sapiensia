package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.audit.SwitchableAuditLog;
import br.com.sergio.sapiensia.audit.SwitchableAuditLogConfig;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The upload route against a real Tomcat: the receive limits, hostile file names, the shape of the answers and what
 * the audit trail and the log may hold. 50 MB is 52,428,800 bytes; bigger bodies are streamed, never held in memory.
 */
@Import({
    TestcontainersConfiguration.class,
    AdminMfaTestSupport.ClockConfig.class,
    SwitchableProductFileStorageConfig.class,
    SwitchableAuditLogConfig.class
})
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=admin-product-file-upload",
            "app.login-limit.ip-max-attempts=1000",
            "app.storage.file.dir=target/test-storage-file-upload"
        })
class AdminProductFileUploadTest {

    private static final long LIMIT = 50L * 1024 * 1024;
    private static final String KEY_PATTERN = "^products/[0-9a-f-]{36}/[0-9a-f-]{36}\\.pdf$";

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
    SwitchableAuditLog auditLog;

    private AdminProductFixtures.Admin account;
    private UUID productId;
    private AdminBrowser admin;

    @BeforeEach
    void setUp() throws Exception {
        clearState();
        account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        productId = AdminProductFixtures.insertProduct(jdbc);
        admin = new AdminBrowser(port);
        assertThat(admin.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
    }

    @AfterEach
    void tearDown() {
        clearState();
    }

    private void clearState() {
        storage.failPuts(false);
        storage.failDeletes(false);
        auditLog.failWrites(false);
        for (String key : storage.keys()) {
            storage.delete(key);
        }
        jdbc.update("delete from product_files");
        jdbc.update("delete from products");
    }

    private String path() {
        return PRODUCTS + "/" + productId + "/file";
    }

    private HttpResponse<String> upload(String filename, String contentType, byte[] content) throws Exception {
        return admin.postMultipart(path(), UploadBodies.of(filename, contentType, content), true);
    }

    private int fileRows() {
        return jdbc.queryForObject("select count(*) from product_files", Integer.class);
    }

    private void assertNothingStored() {
        assertThat(storage.keys()).isEmpty();
        assertThat(fileRows()).isZero();
    }

    private static List<Path> ourTempFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> p.getFileName().toString().startsWith("product-file-"))
                    .toList();
        }
    }

    /** Files of Tomcat's multipart work directories: partial uploads would be left here. */
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
    @DisplayName("SEC-043: arquivo de 52.428.800 bytes é aceito e o de 52.428.801 recebe 413 FILE_TOO_LARGE")
    void shouldAcceptTheExactLimitAndRefuseOneByteMore() throws Exception {
        List<Path> tempBefore = ourTempFiles();
        HttpResponse<String> exact = admin.postMultipart(
                path(),
                UploadBodies.stream("file", "guia.pdf", "application/pdf", UploadBodies.paddedPdf(LIMIT), LIMIT),
                true);

        assertThat(exact.statusCode()).as(exact.body()).isEqualTo(200);
        assertThat(jdbc.queryForMap("select size_bytes, page_count from product_files"))
                .containsEntry("size_bytes", LIMIT)
                .containsEntry("page_count", 1);
        String key = jdbc.queryForObject("select object_key from product_files", String.class);
        assertThat(Files.size(Path.of("target/test-storage-file-upload").resolve(key)))
                .isEqualTo(LIMIT);
        assertThat(ourTempFiles()).isEqualTo(tempBefore);

        clearState();
        HttpResponse<String> over = admin.postMultipart(
                path(),
                UploadBodies.stream(
                        "file", "guia.pdf", "application/pdf", UploadBodies.paddedPdf(LIMIT + 1), LIMIT + 1),
                true);

        assertThat(over.statusCode()).isEqualTo(413);
        assertThat(over.body()).contains("\"errors\":{\"file\":\"FILE_TOO_LARGE\"}");
        assertNothingStored();
        assertThat(ourTempFiles()).isEqualTo(tempBefore);
        assertThat(biggestTomcatTempFile()).as("no partial upload left behind").isLessThan(LIMIT);
    }

    @Test
    @DisplayName("SEC-043: corpo sem fim (chunked) é cortado ao passar do limite, sem guardar nada além dele")
    void shouldCutAnEndlessBodyWhileReceiving() throws Exception {
        long offered = 400L * 1024 * 1024;
        AtomicLong sent = new AtomicLong();
        Integer status = null;
        try {
            HttpResponse<String> response = admin.postMultipart(
                    path(),
                    UploadBodies.stream("file", "guia.pdf", "application/pdf", UploadBodies.filler(offered, sent), -1),
                    true);
            status = response.statusCode();
        } catch (IOException closed) {
            // the server may close the connection once it has cut the body; the 413 path is proved by the test above
        }

        if (status != null) {
            assertThat(status).isEqualTo(413);
        }
        // the cut happens near the limit, far from the end of what the client was ready to send
        assertThat(sent.get()).isGreaterThan(LIMIT - 1).isLessThan(LIMIT + 32L * 1024 * 1024);
        assertNothingStored();
        assertThat(biggestTomcatTempFile()).isLessThan(LIMIT);
    }

    @Test
    @DisplayName("SEC-043: Content-Length acima de 51 MB é recusado com 413 antes de ler o corpo, sem temporário")
    void shouldRefuseAContentLengthAbove51MbWithoutReadingTheBody() throws Exception {
        List<Path> tempBefore = ourTempFiles();
        String request = "POST " + path() + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                + "Cookie: JSESSIONID=" + admin.cookie("JSESSIONID") + "; XSRF-TOKEN=" + admin.cookie("XSRF-TOKEN")
                + "\r\nX-XSRF-TOKEN: " + admin.cookie("XSRF-TOKEN") + "\r\n"
                + "Content-Type: multipart/form-data; boundary=x\r\nContent-Length: " + (60L * 1024 * 1024)
                + "\r\nConnection: close\r\n\r\n--x\r\n";
        String statusLine;
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            // only the headers were sent: an answer proves the server decided without the 60 MB body
            statusLine = new String(socket.getInputStream().readNBytes(12), StandardCharsets.ISO_8859_1);
        }

        assertThat(statusLine).isEqualTo("HTTP/1.1 413");
        assertNothingStored();
        assertThat(ourTempFiles()).isEqualTo(tempBefore);
        assertThat(biggestTomcatTempFile()).isLessThan(LIMIT);
    }

    @Test
    @DisplayName("SEC-047: nome com NUL recusado pelo Tomcat dá 400 FILE_NOT_PDF e nenhum pedaço dele vai a nenhum log")
    void shouldLogNoFragmentOfAFileNameWithANul(CapturedOutput output) throws Exception {
        HttpResponse<String> response =
                upload("fragalphaqzx\u0000fragomegaqzx.pdf", "application/pdf", TestPdfs.pages(1));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"errors\":{\"file\":\"FILE_NOT_PDF\"}");
        assertThat(output.getAll())
                .doesNotContain("fragalphaqzx")
                .doesNotContain("fragomegaqzx")
                .doesNotContain("Invalid file name")
                .doesNotContain("InvalidFileNameException");
        assertNothingStored();
    }

    @Test
    @DisplayName(
            "SEC-047 SEC-043: nomes ../../etc/passwd.pdf, ..\\x.pdf, NUL e x.pdf.exe nunca viram caminho nem sobram")
    void shouldNeverUseTheSubmittedNameAsAPathOrKeep() throws Exception {
        byte[] pdf = TestPdfs.pages(1);
        Map<String, Integer> names = new java.util.LinkedHashMap<>();
        names.put("../../etc/passwd.pdf", 200);
        names.put("..\\x.pdf", 200);
        names.put("a.pdf\u0000.exe", 400);
        names.put("x.pdf.exe", 400);
        for (var entry : names.entrySet()) {
            HttpResponse<String> response = upload(entry.getKey(), "application/pdf", pdf);

            assertThat(response.statusCode()).as(entry.getKey()).isEqualTo(entry.getValue());
            if (entry.getValue() == 400) {
                assertThat(response.body()).contains("\"errors\":{\"file\":\"FILE_NOT_PDF\"}");
            }
        }

        assertThat(storage.keys()).hasSize(1).allSatisfy(key -> assertThat(key).matches(KEY_PATTERN));
        String everythingStored = jdbc.queryForList("select to_jsonb(f)::text from product_files f")
                        .toString()
                + jdbc.queryForList("select to_jsonb(a)::text from audit_events a");
        for (String name : names.keySet()) {
            assertThat(everythingStored).doesNotContain(name);
        }
        assertThat(everythingStored).doesNotContain("passwd").doesNotContain("x.pdf");
    }

    @Test
    @DisplayName("SEC-047: o nome enviado não aparece no log, nem a chave, nem o conteúdo")
    void shouldNeverLogTheSubmittedNameKeyOrContent(CapturedOutput output) throws Exception {
        upload("unique-name-9137.pdf", "application/pdf", TestPdfs.pages(1));
        upload("other-unique-name-9137.txt", "application/pdf", TestPdfs.pages(1));
        // Tomcat itself refuses a NUL in the name, with a message that quotes it: it must not reach the log
        assertThat(upload("nul-unique-name-9137\u0000.pdf", "application/pdf", TestPdfs.pages(1))
                        .statusCode())
                .isEqualTo(400);
        storage.failPuts(true);
        upload("third-unique-name-9137.pdf", "application/pdf", TestPdfs.pages(1));
        storage.failPuts(false);
        String key = jdbc.queryForObject("select object_key from product_files", String.class);

        assertThat(output.getAll())
                .doesNotContain("unique-name-9137")
                .doesNotContain(key)
                .doesNotContain(key.substring(key.lastIndexOf('/') + 1, key.length() - 4))
                .doesNotContain("%PDF-");
    }

    @Test
    @DisplayName("FR-034: a resposta do envio traz o arquivo sem chave de objeto nem URL")
    void shouldNeverReturnTheObjectKeyOrAnUrl() throws Exception {
        HttpResponse<String> response = upload("guia.pdf", "application/pdf", TestPdfs.pages(1));

        assertThat(response.statusCode()).isEqualTo(200);
        String key = jdbc.queryForObject("select object_key from product_files", String.class);
        assertThat(response.body())
                .contains("\"file\":{\"sizeBytes\":")
                .contains("\"pageCount\":1")
                .doesNotContain(key)
                .doesNotContain("products/")
                .doesNotContainIgnoringCase("objectKey")
                .doesNotContainIgnoringCase("url")
                .doesNotContain("http");
        assertThat(admin.get(PRODUCTS + "/" + productId).body())
                .doesNotContain(key)
                .doesNotContainIgnoringCase("objectKey");
    }

    @Test
    @DisplayName("SEC-065: envio aceito e recusado gravam auditoria sem nome, tamanho nem chave")
    void shouldAuditAcceptedAndRefusedUploadsWithoutNameSizeOrKey() throws Exception {
        jdbc.update("delete from audit_events where action = 'ADMIN_PRODUCT_FILE_UPLOADED'");
        byte[] pdf = TestPdfs.pages(1);
        assertThat(upload("nome-unico-5521.pdf", "application/pdf", pdf).statusCode())
                .isEqualTo(200);
        assertThat(upload("nome-unico-5521.exe", "application/pdf", pdf).statusCode())
                .isEqualTo(400);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select result, actor_id, object_type, object_id, to_jsonb(a)::text as text from audit_events a"
                        + " where action = 'ADMIN_PRODUCT_FILE_UPLOADED' order by result");
        assertThat(rows).extracting(r -> r.get("result")).containsExactly("FAILURE", "SUCCESS");
        String key = jdbc.queryForObject("select object_key from product_files", String.class);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("actor_id")).isEqualTo(account.id());
            assertThat(row.get("object_type")).isEqualTo("PRODUCT");
            assertThat(row.get("object_id")).isEqualTo(productId);
            assertThat((String) row.get("text"))
                    .doesNotContain("nome-unico-5521")
                    .doesNotContain(key)
                    .doesNotContain(String.valueOf(pdf.length));
        }
    }

    @Test
    @DisplayName("SEC-043: parte ausente é 400 FILE_REQUIRED e arquivo vazio é 400 FILE_EMPTY")
    void shouldAnswerFileRequiredAndFileEmpty() throws Exception {
        HttpResponse<String> missing = admin.postMultipart(
                path(), UploadBodies.ofPart("other", "guia.pdf", "application/pdf", TestPdfs.pages(1)), true);
        HttpResponse<String> empty = upload("guia.pdf", "application/pdf", new byte[0]);

        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("\"errors\":{\"file\":\"FILE_REQUIRED\"}");
        assertThat(empty.statusCode()).isEqualTo(400);
        assertThat(empty.body()).contains("\"errors\":{\"file\":\"FILE_EMPTY\"}");
        assertNothingStored();
    }

    @Test
    @DisplayName("SEC-043: arquivo que não é PDF é 400 FILE_NOT_PDF e nada é guardado")
    void shouldRefuseAFileThatIsNotAPdf() throws Exception {
        HttpResponse<String> response = upload("guia.pdf", "application/pdf", "not a pdf".getBytes());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"errors\":{\"file\":\"FILE_NOT_PDF\"}");
        assertNothingStored();
    }

    @Test
    @DisplayName("SEC-047: falha do Storage e falha do banco respondem 503 sem corpo e o temporário é apagado")
    void shouldAnswer503WithoutBodyWhenTheStorageOrTheDatabaseFails() throws Exception {
        List<Path> tempBefore = ourTempFiles();
        storage.failPuts(true);
        HttpResponse<String> storageDown = upload("guia.pdf", "application/pdf", TestPdfs.pages(1));
        storage.failPuts(false);
        auditLog.failWrites(true);
        HttpResponse<String> databaseDown = upload("guia.pdf", "application/pdf", TestPdfs.pages(1));
        auditLog.failWrites(false);

        assertThat(storageDown.statusCode()).isEqualTo(503);
        assertThat(storageDown.body()).isEmpty();
        assertThat(databaseDown.statusCode()).isEqualTo(503);
        assertThat(databaseDown.body()).isEmpty();
        assertNothingStored();
        assertThat(ourTempFiles()).isEqualTo(tempBefore);
    }

    @Test
    @DisplayName("SEC-010: GET, PUT e DELETE em /file seguem negados ao Administrador completo")
    void shouldKeepOtherMethodsOnTheFileRouteDenied() throws Exception {
        assertThat(admin.get(path()).statusCode()).isEqualTo(403);
        assertThat(admin.put(path(), "{}").statusCode()).isEqualTo(403);
        assertThat(admin.delete(path()).statusCode()).isEqualTo(403);
    }
}
