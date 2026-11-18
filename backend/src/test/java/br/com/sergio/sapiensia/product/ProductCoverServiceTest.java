package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.SwitchableAuditLog;
import br.com.sergio.sapiensia.audit.SwitchableAuditLogConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;

/** Upload, replacement and removal of the product cover, and the orphan bookkeeping around them (SEC-043, SEC-050). */
@Import({TestcontainersConfiguration.class, SwitchableAuditLogConfig.class, SwitchableProductFileStorageConfig.class})
@SpringBootTest(
        properties = {"test.isolated-context=product-cover-service", "app.storage.file.dir=target/test-storage-cover"})
class ProductCoverServiceTest {

    @Autowired
    ProductCoverService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SwitchableProductFileStorage storage;

    @Autowired
    SwitchableAuditLog auditLog;

    @Autowired
    CoverImageValidator validator;

    @Autowired
    ProductCovers covers;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    PendingObjectDeletions pending;

    @Autowired
    ProductFiles files;

    @Autowired
    Products products;

    @Autowired
    AuditLog audit;

    @Autowired
    PlatformTransactionManager transactionManager;

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
        storage.resumePuts();
        storage.failPuts(false);
        storage.failDeletes(false);
        storage.failGets(false);
        auditLog.failWrites(false);
        for (String key : storage.keys()) {
            storage.delete(key);
        }
        jdbc.update("delete from pending_object_deletions");
        jdbc.update("delete from product_covers");
        jdbc.update("delete from products");
    }

    private UUID insertProduct() {
        return jdbc.queryForObject(
                "insert into products (title, description, category, price_cents)"
                        + " values ('t', 'd', 'OTHER', 1000) returning id",
                UUID.class);
    }

    private Path image(byte[] bytes) throws IOException {
        return Files.write(Files.createTempFile(tmp, "cover", ".bin"), bytes);
    }

    private ProductResponse upload(byte[] bytes) throws IOException {
        return service.upload(productId, image(bytes), adminId, "127.0.0.1");
    }

    private List<String> coverKeys() {
        return jdbc.queryForList("select object_key from product_covers", String.class);
    }

    private List<String> pendingKeys() {
        return jdbc.queryForList("select object_key from pending_object_deletions", String.class);
    }

    private long auditCount(String action, String result) {
        return jdbc.queryForObject(
                "select count(*) from audit_events where action = ? and object_id = ? and result = ?",
                Long.class,
                action,
                productId,
                result);
    }

    private static String sha256Prefix(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
    }

    @Test
    @DisplayName("SEC-043 SEC-065: upload stores the cleaned bytes, one row with format, size, dimensions and version")
    void shouldStoreCoverRow() throws Exception {
        ProductResponse response = upload(TestImages.png(30, 20));

        Map<String, Object> row = jdbc.queryForMap("select * from product_covers where product_id = ?", productId);
        String key = (String) row.get("object_key");
        byte[] stored = storage.get(key).orElseThrow();
        assertThat(key).startsWith("covers/" + productId + "/").endsWith(".png");
        assertThat(row.get("media_type")).isEqualTo("image/png");
        assertThat(row.get("width")).isEqualTo(30);
        assertThat(row.get("height")).isEqualTo(20);
        assertThat(row.get("size_bytes")).isEqualTo(stored.length);
        assertThat(((String) row.get("version")).trim()).isEqualTo(sha256Prefix(stored));
        assertThat(storage.keys()).containsExactly(key);
        assertThat(pendingKeys()).isEmpty();
        assertThat(auditCount("ADMIN_PRODUCT_COVER_UPLOADED", "SUCCESS")).isEqualTo(1);
        assertThat(response.cover()).isNotNull();
        assertThat(response.cover().width()).isEqualTo(30);
        assertThat(response.cover().sizeBytes()).isEqualTo(stored.length);
        assertThat(response.cover().version()).isEqualTo(sha256Prefix(stored));
    }

    @Test
    @DisplayName("SEC-050: replacing deletes the old object and leaves no pending key")
    void shouldReplaceCover() throws Exception {
        upload(TestImages.png(10, 10));
        String oldKey = coverKeys().get(0);

        upload(TestImages.jpeg(12, 12));

        assertThat(coverKeys()).hasSize(1).doesNotContain(oldKey);
        assertThat(storage.keys()).containsExactlyElementsOf(coverKeys());
        assertThat(pendingKeys()).isEmpty();
        assertThat(jdbc.queryForObject("select media_type from product_covers", String.class))
                .isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("SEC-050: a failed put leaves no cover row and no pending key when the cleanup delete works")
    void shouldLeaveNothingWhenPutFails() throws Exception {
        storage.failPuts(true);

        assertThatThrownBy(() -> upload(TestImages.png(10, 10))).isInstanceOf(ProductFileStorageException.class);

        assertThat(coverKeys()).isEmpty();
        assertThat(pendingKeys()).isEmpty();
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    @DisplayName("SEC-050: a failed put and a failed cleanup delete leave the new key registered")
    void shouldRegisterNewKeyWhenPutAndCleanupFail() throws Exception {
        storage.failPuts(true);
        storage.failDeletes(true);

        assertThatThrownBy(() -> upload(TestImages.png(10, 10))).isInstanceOf(ProductFileStorageException.class);

        assertThat(coverKeys()).isEmpty();
        assertThat(pendingKeys()).hasSize(1);
        assertThat(pendingKeys().get(0)).startsWith("covers/" + productId + "/");
    }

    @Test
    @DisplayName("SEC-050: a Product removed between validation and the lock gets its new object deleted")
    void shouldDeleteNewObjectWhenTransactionFails() throws Exception {
        assertThat(runRemovedWhilePutPaused(false)).isInstanceOf(ProductNotFoundException.class);

        assertThat(storage.keys()).isEmpty();
        assertThat(pendingKeys()).isEmpty();
    }

    @Test
    @DisplayName("SEC-050: the same failure with a failing delete leaves the new key registered")
    void shouldRegisterNewKeyWhenTransactionAndDeleteFail() throws Exception {
        assertThat(runRemovedWhilePutPaused(true)).isInstanceOf(ProductNotFoundException.class);

        assertThat(pendingKeys()).hasSize(1);
        assertThat(storage.keys()).containsExactlyElementsOf(pendingKeys());
    }

    private Throwable runRemovedWhilePutPaused(boolean failDeletes) throws Exception {
        Path file = image(TestImages.png(10, 10));
        SwitchableProductFileStorage.Pause pause = storage.pausePuts();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ProductResponse> result =
                    executor.submit(() -> service.upload(productId, file, adminId, "127.0.0.1"));
            assertThat(pause.awaitReached()).isTrue();
            jdbc.update("delete from products where id = ?", productId);
            storage.failDeletes(failDeletes);
            pause.release();
            try {
                result.get();
                return null;
            } catch (java.util.concurrent.ExecutionException e) {
                return e.getCause();
            }
        } finally {
            pause.release();
            executor.shutdownNow();
        }
    }

    /** Throws on the given commit (1 = the pending row, 2 = the swap), after really committing it or rolling it back. */
    private static PlatformTransactionManager commitThenThrowOn(
            PlatformTransactionManager delegate, int commitNumber, boolean committed, Runnable afterCommit) {
        int[] commits = {0};
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
                return delegate.getTransaction(definition);
            }

            @Override
            public void commit(TransactionStatus status) throws TransactionException {
                boolean fails = ++commits[0] == commitNumber;
                if (fails && !committed) {
                    delegate.rollback(status);
                } else {
                    delegate.commit(status);
                }
                if (fails) {
                    afterCommit.run();
                    throw new DataAccessResourceFailureException("commit reply lost");
                }
            }

            @Override
            public void rollback(TransactionStatus status) throws TransactionException {
                delegate.rollback(status);
            }
        };
    }

    @Test
    @DisplayName(
            "SEC-050: a commit that fails after the database committed keeps the object, the row and no pending key")
    void shouldKeepCommittedCoverWhenCommitReplyIsLost() throws Exception {
        ProductCoverService lossy = new ProductCoverService(
                validator,
                storage,
                covers,
                pending,
                files,
                products,
                audit,
                commitThenThrowOn(transactionManager, 2, true, () -> {}));

        assertThatThrownBy(() -> lossy.upload(productId, image(TestImages.png(10, 10)), adminId, "127.0.0.1"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(coverKeys()).hasSize(1);
        assertThat(storage.keys()).containsExactlyElementsOf(coverKeys());
        assertThat(pendingKeys()).isEmpty();
    }

    @Test
    @DisplayName("SEC-050: when the re-read after a failed commit also fails, the key stays pending and stored")
    void shouldLeaveKeyPendingWhenRereadFails() throws Exception {
        AtomicBoolean down = new AtomicBoolean();
        ProductCovers failingCovers = new ProductCovers(jdbcClient) {
            @Override
            Optional<String> currentKey(UUID id) {
                if (down.get()) {
                    throw new IllegalStateException("db down");
                }
                return super.currentKey(id);
            }
        };
        ProductCoverService lossy = new ProductCoverService(
                validator,
                storage,
                failingCovers,
                pending,
                files,
                products,
                audit,
                commitThenThrowOn(transactionManager, 2, false, () -> down.set(true)));

        assertThatThrownBy(() -> lossy.upload(productId, image(TestImages.png(10, 10)), adminId, "127.0.0.1"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(storage.keys()).hasSize(1);
        assertThat(pendingKeys()).containsExactlyElementsOf(storage.keys());
    }

    @Test
    @DisplayName("SEC-050: a failed delete of the old object after commit keeps the new row and registers the old key")
    void shouldRegisterOldKeyWhenDeleteAfterCommitFails() throws Exception {
        upload(TestImages.png(10, 10));
        String oldKey = coverKeys().get(0);
        storage.failDeletes(true);

        upload(TestImages.png(11, 11));

        String newKey = coverKeys().get(0);
        assertThat(newKey).isNotEqualTo(oldKey);
        assertThat(pendingKeys()).containsExactly(oldKey);
        assertThat(storage.keys()).containsExactlyInAnyOrder(oldKey, newKey);
    }

    private void publishWithCover() throws Exception {
        upload(TestImages.png(10, 10));
        jdbc.update("update products set status = 'PUBLISHED', first_published_at = now() where id = ?", productId);
    }

    private String statusOfProduct() {
        return jdbc.queryForObject("select status from products where id = ?", String.class, productId);
    }

    @Test
    @DisplayName("FR-011: a failed put on a PUBLISHED Product keeps the old Capa row and object")
    void shouldKeepTheOldCoverWhenStoringTheNewOneFails() throws Exception {
        publishWithCover();
        String oldKey = coverKeys().get(0);
        String oldVersion = jdbc.queryForObject("select version from product_covers", String.class);
        storage.failPuts(true);

        assertThatThrownBy(() -> upload(TestImages.png(12, 12))).isInstanceOf(ProductFileStorageException.class);

        assertThat(coverKeys()).containsExactly(oldKey);
        assertThat(jdbc.queryForObject("select version from product_covers", String.class))
                .isEqualTo(oldVersion);
        assertThat(storage.keys()).containsExactly(oldKey);
        assertThat(pendingKeys()).isEmpty();
        assertThat(statusOfProduct()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("FR-011: a failed swap transaction on a PUBLISHED Product keeps the old Capa and drops the new object")
    void shouldKeepTheOldCoverWhenTheSwapTransactionFails() throws Exception {
        publishWithCover();
        String oldKey = coverKeys().get(0);
        String oldVersion = jdbc.queryForObject("select version from product_covers", String.class);
        ProductCoverService lossy = new ProductCoverService(
                validator,
                storage,
                covers,
                pending,
                files,
                products,
                audit,
                commitThenThrowOn(transactionManager, 2, false, () -> {}));

        assertThatThrownBy(() -> lossy.upload(productId, image(TestImages.png(12, 12)), adminId, "127.0.0.1"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(coverKeys()).containsExactly(oldKey);
        assertThat(jdbc.queryForObject("select version from product_covers", String.class))
                .isEqualTo(oldVersion);
        assertThat(storage.keys()).containsExactly(oldKey);
        assertThat(pendingKeys()).isEmpty();
        assertThat(statusOfProduct()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("FR-011: when the old object cannot be deleted, the new Capa is served and the old key is pending")
    void shouldServeTheNewCoverWhenDeletingTheOldObjectFails() throws Exception {
        publishWithCover();
        String oldKey = coverKeys().get(0);
        storage.failDeletes(true);

        upload(TestImages.png(12, 12));

        String newKey = coverKeys().get(0);
        assertThat(newKey).isNotEqualTo(oldKey);
        StoredCover served = service.publishedCover(productId).orElseThrow();
        assertThat(served.objectKey()).isEqualTo(newKey);
        assertThat(service.read(served)).isEqualTo(storage.get(newKey).orElseThrow());
        assertThat(pendingKeys()).containsExactly(oldKey);
        assertThat(statusOfProduct()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("SEC-050: the current key never appears in the pending list")
    void shouldNeverRegisterCurrentKey() throws Exception {
        upload(TestImages.png(10, 10));
        storage.failDeletes(true);
        upload(TestImages.png(11, 11));
        storage.failPuts(true);
        assertThatThrownBy(() -> upload(TestImages.png(12, 12))).isInstanceOf(ProductFileStorageException.class);

        assertThat(pendingKeys()).doesNotContainAnyElementsOf(coverKeys());
        assertThat(coverKeys()).hasSize(1);
    }

    @Test
    @DisplayName("SEC-043 SEC-065: a rejected image is audited as FAILURE and nothing reaches the storage")
    void shouldAuditRejectionAndStoreNothing() throws Exception {
        assertThatThrownBy(() -> upload("not an image".getBytes()))
                .isInstanceOfSatisfying(
                        ProductCoverRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProductCoverRejection.COVER_NOT_IMAGE));

        assertThat(storage.keys()).isEmpty();
        assertThat(coverKeys()).isEmpty();
        assertThat(pendingKeys()).isEmpty();
        assertThat(auditCount("ADMIN_PRODUCT_COVER_UPLOADED", "FAILURE")).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-043: upload for a missing Product is ProductNotFoundException")
    void shouldRejectMissingProductOnUpload() {
        assertThatThrownBy(() -> service.upload(UUID.randomUUID(), image(TestImages.png(5, 5)), adminId, "127.0.0.1"))
                .isInstanceOf(ProductNotFoundException.class);
        assertThat(storage.keys()).isEmpty();
    }

    @Test
    @DisplayName("SEC-050 SEC-065: removal deletes row and object and audits ADMIN_PRODUCT_COVER_REMOVED")
    void shouldRemoveCover() throws Exception {
        upload(TestImages.png(10, 10));

        service.remove(productId, adminId, "127.0.0.1");

        assertThat(coverKeys()).isEmpty();
        assertThat(storage.keys()).isEmpty();
        assertThat(pendingKeys()).isEmpty();
        assertThat(auditCount("ADMIN_PRODUCT_COVER_REMOVED", "SUCCESS")).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-050: removal whose delete fails keeps the old key registered")
    void shouldRegisterKeyWhenRemoveDeleteFails() throws Exception {
        upload(TestImages.png(10, 10));
        String key = coverKeys().get(0);
        storage.failDeletes(true);

        service.remove(productId, adminId, "127.0.0.1");

        assertThat(coverKeys()).isEmpty();
        assertThat(pendingKeys()).containsExactly(key);
    }

    @Test
    @DisplayName("SEC-043: removing without a cover does not fail; a missing Product is ProductNotFoundException")
    void shouldRemoveIdempotently() {
        service.remove(productId, adminId, "127.0.0.1");

        assertThatThrownBy(() -> service.remove(UUID.randomUUID(), adminId, "127.0.0.1"))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("SEC-043: anyCover and publishedCover tell a draft from a published Product; read fails when missing")
    void shouldFindCoverByPublication() throws Exception {
        upload(TestImages.png(10, 10));

        assertThat(service.anyCover(productId)).isPresent();
        assertThat(service.publishedCover(productId)).isEmpty();
        jdbc.update("update products set status = 'PUBLISHED', first_published_at = now() where id = ?", productId);
        StoredCover cover = service.publishedCover(productId).orElseThrow();
        assertThat(cover.mediaType()).isEqualTo("image/png");
        assertThat(service.read(cover)).isEqualTo(storage.get(cover.objectKey()).orElseThrow());

        storage.delete(cover.objectKey());
        assertThatThrownBy(() -> service.read(cover)).isInstanceOf(ProductCoverMissingException.class);
    }

    @Test
    @DisplayName("SEC-050: a failed write of the cleaned bytes leaves no temporary file behind")
    void shouldDeleteTempFileWhenWriteFails() throws IOException {
        Set<Path> before = coverTempFiles();

        assertThatThrownBy(() -> ProductCoverService.writeTemp(null)).isInstanceOf(NullPointerException.class);

        Set<Path> created = coverTempFiles();
        created.removeAll(before);
        assertThat(created).isEmpty();
    }

    private static Set<Path> coverTempFiles() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> p.getFileName().toString().startsWith("cover-"))
                    .collect(Collectors.toCollection(HashSet::new));
        }
    }
}
