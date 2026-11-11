package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.audit.SwitchableAuditLog;
import br.com.sergio.sapiensia.audit.SwitchableAuditLogConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The upload flow of the product PDF: validate, put, short swap transaction, delete the old object after commit. */
@Import({TestcontainersConfiguration.class, SwitchableAuditLogConfig.class, SwitchableProductFileStorageConfig.class})
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        properties = {"test.isolated-context=product-file-service", "app.storage.file.dir=target/test-storage-service"})
class ProductFileServiceTest {

    private static final String SUBMITTED_NAME = "unique-submitted-name-7731.pdf";

    @Autowired
    ProductFileService service;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    Products products;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SwitchableProductFileStorage storage;

    @Autowired
    SwitchableAuditLog auditLog;

    @TempDir
    Path tmp;

    private UUID productId;
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resetState();
        productId = insertProduct();
    }

    @AfterEach
    void tearDown() {
        resetState();
    }

    private void resetState() {
        storage.failPuts(false);
        storage.failDeletes(false);
        storage.gatePuts(0);
        auditLog.failWrites(false);
        for (String key : storage.keys()) {
            storage.delete(key);
        }
        jdbc.update("delete from product_files");
        jdbc.update("delete from products");
    }

    private UUID insertProduct() {
        return jdbc.queryForObject(
                "insert into products (title, description, category, price_cents)"
                        + " values ('t', 'd', 'OTHER', 1000) returning id",
                UUID.class);
    }

    private Path pdf(int pages) throws IOException {
        return Files.write(Files.createTempFile(tmp, "up", ".bin"), TestPdfs.pages(pages));
    }

    private ProductResponse upload(UUID id, Path file) {
        return service.upload(id, file, SUBMITTED_NAME, "application/pdf", adminId, "127.0.0.1");
    }

    private List<String> rowKeys() {
        return jdbc.queryForList("select object_key from product_files", String.class);
    }

    private long auditCount(String result) {
        return jdbc.queryForObject(
                "select count(*) from audit_events where action = 'ADMIN_PRODUCT_FILE_UPLOADED'"
                        + " and object_id = ? and result = ?",
                Long.class,
                productId,
                result);
    }

    @Test
    @DisplayName("SEC-047: first upload writes one row and one object and returns the file info")
    void shouldStoreFirstUpload() throws Exception {
        Path file = pdf(2);
        ProductResponse response = upload(productId, file);

        assertThat(response.file()).isNotNull();
        assertThat(response.file().pageCount()).isEqualTo(2);
        assertThat(response.file().sizeBytes()).isEqualTo(Files.size(file));
        assertThat(rowKeys()).hasSize(1);
        assertThat(storage.keys()).containsExactlyElementsOf(rowKeys());
        assertThat(Files.exists(file)).as("the temp file belongs to the caller").isTrue();
        assertThat(auditCount("SUCCESS")).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-047: a Product without file has file null")
    void shouldReturnNullFileWhenNone() {
        assertThat(products.find(productId))
                .get()
                .extracting(ProductResponse::file)
                .isNull();
    }

    @Test
    @DisplayName("SEC-047: uma só versão atual: the second upload swaps the row and deletes the previous object")
    void shouldSwapRowAndDeleteOldObject() throws Exception {
        upload(productId, pdf(1));
        String oldKey = rowKeys().get(0);

        upload(productId, pdf(3));

        assertThat(rowKeys()).hasSize(1).doesNotContain(oldKey);
        assertThat(storage.keys()).containsExactlyElementsOf(rowKeys());
        assertThat(storage.keys()).doesNotContain(oldKey);
    }

    @Test
    @DisplayName("SEC-047: storage put failure throws and leaves the database unchanged")
    void shouldLeaveDatabaseUnchangedWhenPutFails() throws Exception {
        storage.failPuts(true);

        assertThatThrownBy(() -> upload(productId, pdf(1))).isInstanceOf(ProductFileStorageException.class);

        assertThat(rowKeys()).isEmpty();
        assertThat(storage.keys()).isEmpty();
        assertThat(auditCount("SUCCESS")).isZero();
    }

    @Test
    @DisplayName(
            "SEC-047 SEC-065: audit failure during the swap deletes the new object and keeps the old row and object")
    void shouldCompensateWhenSwapFails() throws Exception {
        upload(productId, pdf(1));
        String oldKey = rowKeys().get(0);
        auditLog.failWrites(true);

        assertThatThrownBy(() -> upload(productId, pdf(2))).isInstanceOf(DataAccessException.class);

        assertThat(rowKeys()).containsExactly(oldKey);
        assertThat(storage.keys()).containsExactly(oldKey);
        assertThat(jdbc.queryForObject("select page_count from product_files", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-047: failing to delete the old object does not change the result and logs no key or name")
    void shouldIgnoreOldDeleteFailure(CapturedOutput output) throws Exception {
        upload(productId, pdf(1));
        String oldKey = rowKeys().get(0);
        storage.failDeletes(true);

        ProductResponse response = upload(productId, pdf(2));

        assertThat(response.file().pageCount()).isEqualTo(2);
        assertThat(rowKeys()).hasSize(1).doesNotContain(oldKey);
        assertThat(storage.keys()).contains(oldKey).hasSize(2); // the intended orphan stays in storage
        assertThat(output.getAll())
                .contains("could not be deleted")
                .contains(productId.toString())
                .doesNotContain(oldKey)
                .doesNotContain(SUBMITTED_NAME);
    }

    @Test
    @DisplayName("SEC-047: two concurrent uploads leave one row and exactly one object")
    void shouldLeaveOneRowAndOneObjectOnConcurrentUploads() throws Exception {
        Path first = pdf(1);
        Path second = pdf(2);
        CountDownLatch start = new CountDownLatch(1);
        // Both uploads must have validated and stored their object before either enters the swap transaction.
        storage.gatePuts(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> uploadWhenReady(start, first));
            Future<?> b = pool.submit(() -> uploadWhenReady(start, second));
            start.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(rowKeys()).hasSize(1);
        assertThat(storage.keys()).containsExactlyElementsOf(rowKeys());
    }

    // The validator runs one check at a time and answers "busy" to the other caller: retry like a client would.
    private void uploadWhenReady(CountDownLatch start, Path file) {
        try {
            start.await();
            for (int attempt = 0; attempt < 50; attempt++) {
                try {
                    upload(productId, file);
                    return;
                } catch (ValidatorBusyException e) {
                    Thread.sleep(20);
                }
            }
            throw new IllegalStateException("validator stayed busy");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("SEC-047: upload refuses to run inside a caller's transaction and stores nothing")
    void shouldRefuseToRunInsideTransaction() throws Exception {
        Path file = pdf(1);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outer.execute(status -> upload(productId, file)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(storage.keys()).isEmpty();
        assertThat(rowKeys()).isEmpty();
    }

    @Test
    @DisplayName("SEC-047: a nonexistent Product throws ProductNotFoundException and stores nothing")
    void shouldNotStoreForMissingProduct() throws Exception {
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> upload(missing, pdf(1))).isInstanceOf(ProductNotFoundException.class);

        assertThat(storage.keys()).isEmpty();
        assertThat(rowKeys()).isEmpty();
    }

    @Test
    @DisplayName("SEC-043 SEC-065: a rejected file writes a FAILURE audit event and stores nothing")
    void shouldAuditRejectionAndStoreNothing() throws Exception {
        Path notPdf = Files.write(Files.createTempFile(tmp, "up", ".bin"), "hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> upload(productId, notPdf))
                .isInstanceOfSatisfying(
                        ProductFileRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProductFileRejection.FILE_NOT_PDF));

        assertThat(auditCount("FAILURE")).isEqualTo(1);
        assertThat(auditCount("SUCCESS")).isZero();
        assertThat(storage.keys()).isEmpty();
        assertThat(rowKeys()).isEmpty();
    }

    @Test
    @DisplayName("SEC-065: audit events never carry the file name, size, key or content")
    void shouldKeepAuditFreeOfFileData() throws Exception {
        upload(productId, pdf(1));
        String key = rowKeys().get(0);

        List<String> rows = jdbc.queryForList(
                "select concat_ws(' ', actor_id, action, object_type, object_id, result, correlation_id, origin)"
                        + " from audit_events where object_id = ?",
                String.class,
                productId);

        assertThat(rows).isNotEmpty();
        assertThat(String.join(" ", rows)).doesNotContain(SUBMITTED_NAME).doesNotContain(key);
    }

    @Test
    @DisplayName("SEC-008: a Product that was ever published refuses a new file, stores nothing and audits a FAILURE")
    void shouldRefuseAFileForAProductThatWasEverPublished() throws Exception {
        jdbc.update("update products set status = 'PUBLISHED', first_published_at = now() where id = ?", productId);

        assertThatThrownBy(() -> upload(productId, pdf(1)))
                .isInstanceOfSatisfying(
                        ProductFileRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProductFileRejection.PUBLISHED_BEFORE));

        assertThat(auditCount("FAILURE")).isEqualTo(1);
        assertThat(auditCount("SUCCESS")).isZero();
        assertThat(storage.keys()).isEmpty();
        assertThat(rowKeys()).isEmpty();
    }
}
