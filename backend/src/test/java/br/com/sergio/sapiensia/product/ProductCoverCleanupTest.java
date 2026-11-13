package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** The recurring cleanup of orphan cover objects recorded in pending_object_deletions (SEC-050). */
@Import({TestcontainersConfiguration.class, SwitchableProductFileStorageConfig.class})
@SpringBootTest(
        properties = {"test.isolated-context=product-cover-cleanup", "app.storage.file.dir=target/test-storage-cleanup"
        })
class ProductCoverCleanupTest {

    @Autowired
    ProductCoverCleanup.Cleaner cleaner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SwitchableProductFileStorage storage;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        resetState();
    }

    @AfterEach
    void tearDown() {
        resetState();
    }

    private void resetState() {
        storage.failDeletes(false);
        for (String key : storage.keys()) {
            storage.delete(key);
        }
        jdbc.update("delete from pending_object_deletions");
        jdbc.update("delete from product_covers");
        jdbc.update("delete from products");
    }

    private String storedKey() throws IOException {
        String key = "covers/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".png";
        Path file = Files.write(Files.createTempFile(tmp, "o", ".bin"), new byte[] {1, 2, 3});
        storage.put(key, file, 3, MediaType.IMAGE_PNG);
        return key;
    }

    private void pend(String key, int minutesAgo) {
        jdbc.update(
                "insert into pending_object_deletions (object_key, created_at)"
                        + " values (?, now() - make_interval(mins => ?))",
                key,
                minutesAgo);
    }

    private long pendingCount() {
        return jdbc.queryForObject("select count(*) from pending_object_deletions", Long.class);
    }

    @Test
    @DisplayName("SEC-050: a pending key older than 15 minutes is deleted from the storage and the table")
    void shouldDeleteOldPending() throws Exception {
        String key = storedKey();
        pend(key, 16);

        assertThat(cleaner.deleteOrphans()).isEqualTo(1);

        assertThat(storage.keys()).doesNotContain(key);
        assertThat(pendingCount()).isZero();
    }

    @Test
    @DisplayName("SEC-050: a pending key of 5 minutes is left alone (an upload may still be running)")
    void shouldKeepRecentPending() throws Exception {
        String key = storedKey();
        pend(key, 5);

        assertThat(cleaner.deleteOrphans()).isZero();

        assertThat(storage.keys()).contains(key);
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-050: a pending key that is the current cover is never deleted from the storage")
    void shouldNeverDeleteCurrentCover() throws Exception {
        UUID productId = jdbc.queryForObject(
                "insert into products (title, description, category, price_cents)"
                        + " values ('t', 'd', 'OTHER', 1000) returning id",
                UUID.class);
        String key = "covers/" + productId + "/" + UUID.randomUUID() + ".png";
        Path file = Files.write(Files.createTempFile(tmp, "o", ".bin"), new byte[] {1, 2, 3});
        storage.put(key, file, 3, MediaType.IMAGE_PNG);
        jdbc.update(
                "insert into product_covers (product_id, object_key, media_type, size_bytes, width, height, version)"
                        + " values (?, ?, 'image/png', 3, 1, 1, '0123456789abcdef')",
                productId,
                key);
        pend(key, 60);

        assertThat(cleaner.deleteOrphans()).isZero();

        assertThat(storage.keys()).contains(key);
        assertThat(jdbc.queryForObject("select count(*) from product_covers", Long.class))
                .isEqualTo(1);
        assertThat(pendingCount()).as("the stale pending row is dropped").isZero();
    }

    @Test
    @DisplayName("SEC-050: a pending key whose object is already gone counts as deleted")
    void shouldCountMissingObjectAsDeleted() {
        pend("covers/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".png", 30);

        assertThat(cleaner.deleteOrphans()).isEqualTo(1);

        assertThat(pendingCount()).isZero();
    }

    @Test
    @DisplayName("SEC-050: a failing storage delete keeps the pending row for the next run")
    void shouldKeepRowWhenDeleteFails() throws Exception {
        String key = storedKey();
        pend(key, 30);
        storage.failDeletes(true);

        assertThat(cleaner.deleteOrphans()).isZero();

        assertThat(pendingCount()).isEqualTo(1);
        assertThat(storage.keys()).contains(key);
    }
}
