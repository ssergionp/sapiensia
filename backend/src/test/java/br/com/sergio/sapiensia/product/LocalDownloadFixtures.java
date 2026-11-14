package br.com.sergio.sapiensia.product;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** Lets tests of other packages store a file in the (package-private) disk storage and get a local download link. */
public final class LocalDownloadFixtures {

    private LocalDownloadFixtures() {}

    /** Stores the bytes as a Product file and returns the link the disk storage issues for them (60 s). */
    public static String storeAndLink(ApplicationContext context, byte[] bytes, String fileName) throws IOException {
        FileProductFileStorage storage = context.getBean(FileProductFileStorage.class);
        Path source = Files.createTempFile("local-download", ".pdf");
        try {
            Files.write(source, bytes);
            String key = ProductFileKeys.newKey(UUID.randomUUID());
            storage.put(key, source, bytes.length, MediaType.APPLICATION_PDF);
            return storage.signedDownloadUrl(key, java.time.Duration.ofSeconds(60), fileName)
                    .orElseThrow();
        } finally {
            Files.deleteIfExists(source);
        }
    }

    /**
     * Stores the bytes in the disk storage under a new key of the Product and inserts the {@code product_files} row
     * that points to it, as an upload of the panel would. Returns the {@code uploaded_at} of the row.
     */
    public static Instant storeProductFile(ApplicationContext context, JdbcTemplate jdbc, UUID productId, byte[] bytes)
            throws IOException {
        FileProductFileStorage storage = context.getBean(FileProductFileStorage.class);
        Path source = Files.createTempFile("product-file", ".pdf");
        try {
            Files.write(source, bytes);
            String key = ProductFileKeys.newKey(productId);
            storage.put(key, source, bytes.length, MediaType.APPLICATION_PDF);
            return jdbc.queryForObject(
                            "insert into product_files (product_id, object_key, size_bytes, page_count)"
                                    + " values (?, ?, ?, 1) returning uploaded_at",
                            Timestamp.class,
                            productId,
                            key,
                            bytes.length)
                    .toInstant();
        } finally {
            Files.deleteIfExists(source);
        }
    }

    /**
     * Replaces the Product file through the real upload flow (the swap), with a valid PDF of {@code pages} pages.
     * Returns the new {@code uploaded_at}.
     */
    public static Instant replaceProductFile(ApplicationContext context, JdbcTemplate jdbc, UUID productId, int pages)
            throws IOException {
        ProductFileService service = context.getBean(ProductFileService.class);
        Path source = Files.createTempFile("product-file-new", ".pdf");
        try {
            Files.write(source, TestPdfs.pages(pages));
            service.upload(productId, source, "new.pdf", "application/pdf", UUID.randomUUID(), "127.0.0.1", true);
            return jdbc.queryForObject(
                            "select uploaded_at from product_files where product_id = ?", Timestamp.class, productId)
                    .toInstant();
        } finally {
            Files.deleteIfExists(source);
        }
    }

    /** Runs the orphan cleanup once, as the hourly task does. */
    public static void runObjectCleanup(ApplicationContext context) {
        context.getBean(ProductCoverCleanup.Cleaner.class).run();
    }

    /** Inserts a {@code product_files} row whose object was never stored (the Storage lost it). */
    public static void insertMissingObjectRow(JdbcTemplate jdbc, UUID productId) {
        jdbc.update(
                "insert into product_files (product_id, object_key, size_bytes, page_count) values (?, ?, 10, 1)",
                productId,
                ProductFileKeys.newKey(productId));
    }

    public static boolean hasLocalDownloadController(ApplicationContext context) {
        return context.getBeanNamesForType(LocalDownloadController.class).length > 0;
    }
}
