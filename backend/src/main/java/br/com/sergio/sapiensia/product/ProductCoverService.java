package br.com.sergio.sapiensia.product;

import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * Stores, replaces and removes the cover image of a Product. Same shape as {@link ProductFileService}, plus one rule:
 * every key that may become an orphan object is written to {@code pending_object_deletions} before it exists in the
 * storage or stops being the current one, so a crash or a storage outage in the middle never leaves an object that
 * nobody knows about. A recurring task deletes the recorded keys later. Nothing here logs a key, URL or file name.
 */
@Service
class ProductCoverService {

    private static final Logger log = LoggerFactory.getLogger(ProductCoverService.class);

    private record Swapped(Optional<String> oldKey, ProductResponse product) {}

    private final CoverImageValidator validator;
    private final ProductFileStorage storage;
    private final ProductCovers covers;
    private final PendingObjectDeletions pending;
    private final ProductFiles files;
    private final Products products;
    private final AuditLog audit;
    private final TransactionTemplate transaction;

    ProductCoverService(
            CoverImageValidator validator,
            ProductFileStorage storage,
            ProductCovers covers,
            PendingObjectDeletions pending,
            ProductFiles files,
            Products products,
            AuditLog audit,
            PlatformTransactionManager transactionManager) {
        this.validator = validator;
        this.storage = storage;
        this.covers = covers;
        this.pending = pending;
        this.files = files;
        this.products = products;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    ProductResponse upload(UUID productId, Path tempFile, UUID administratorId, String origin) {
        requireNoTransaction();
        if (!products.exists(productId)) {
            throw new ProductNotFoundException();
        }
        CoverCheck check = validator.check(tempFile);
        if (check instanceof CoverCheck.Rejected rejected) {
            audit.recordIndependent(entry(
                    AuditAction.ADMIN_PRODUCT_COVER_UPLOADED, AuditResult.FAILURE, administratorId, productId, origin));
            throw new ProductCoverRejectedException(rejected.reason());
        }
        CoverCheck.Valid valid = (CoverCheck.Valid) check;
        Path cleanedFile = writeTemp(valid.cleaned());
        try {
            return store(productId, valid, cleanedFile, administratorId, origin);
        } finally {
            deleteQuietly(cleanedFile);
        }
    }

    private ProductResponse store(
            UUID productId, CoverCheck.Valid valid, Path cleanedFile, UUID administratorId, String origin) {
        String newKey = ProductFileKeys.newCoverKey(productId, valid.format());
        int size = valid.cleaned().length;
        String version = version(valid.cleaned());
        // Registered before the object exists: whatever happens next, the key is known.
        transaction.executeWithoutResult(status -> pending.add(newKey));
        Swapped swapped;
        try {
            storage.put(
                    newKey,
                    cleanedFile,
                    size,
                    MediaType.parseMediaType(valid.format().mediaType()));
            swapped = transaction.execute(status -> {
                if (!files.lockProduct(productId)) {
                    throw new ProductNotFoundException();
                }
                Optional<String> previous = covers.currentKey(productId);
                covers.upsert(
                        productId, newKey, valid.format().mediaType(), size, valid.width(), valid.height(), version);
                pending.remove(newKey);
                previous.ifPresent(pending::add);
                audit.recordInTransaction(entry(
                        AuditAction.ADMIN_PRODUCT_COVER_UPLOADED,
                        AuditResult.SUCCESS,
                        administratorId,
                        productId,
                        origin));
                return new Swapped(previous, products.find(productId).orElseThrow(ProductNotFoundException::new));
            });
        } catch (RuntimeException e) {
            // Nothing was committed (or the commit failed): the old row still rules, so drop the new object; the row
            // in the pending list goes only if that worked. Accepted residual risk, as in the PDF flow: a commit that
            // throws after the database really committed makes this delete the object the row now points to.
            if (deleteBestEffort(newKey, productId)) {
                forgetBestEffort(newKey, productId);
            }
            throw e;
        }
        swapped.oldKey().ifPresent(key -> {
            if (deleteBestEffort(key, productId)) {
                forgetBestEffort(key, productId);
            }
        });
        return swapped.product();
    }

    void remove(UUID productId, UUID administratorId, String origin) {
        requireNoTransaction();
        if (!products.exists(productId)) {
            throw new ProductNotFoundException();
        }
        Optional<String> removed = transaction.execute(status -> {
            if (!files.lockProduct(productId)) {
                throw new ProductNotFoundException();
            }
            Optional<String> current = covers.currentKey(productId);
            current.ifPresent(key -> {
                pending.add(key);
                covers.delete(productId);
                audit.recordInTransaction(entry(
                        AuditAction.ADMIN_PRODUCT_COVER_REMOVED,
                        AuditResult.SUCCESS,
                        administratorId,
                        productId,
                        origin));
            });
            return current;
        });
        removed.ifPresent(key -> {
            if (deleteBestEffort(key, productId)) {
                forgetBestEffort(key, productId);
            }
        });
    }

    /** The cover of a PUBLISHED Product; empty for any other state, so the caller cannot tell them apart. */
    Optional<StoredCover> publishedCover(UUID productId) {
        return covers.findPublished(productId);
    }

    /** The cover whatever the status of the Product (panel preview). */
    Optional<StoredCover> anyCover(UUID productId) {
        return covers.find(productId);
    }

    byte[] read(StoredCover cover) {
        return storage.get(cover.objectKey()).orElseThrow(ProductCoverMissingException::new);
    }

    /** A failed delete only leaves a recorded orphan; it must not change the outcome. */
    private boolean deleteBestEffort(String key, UUID productId) {
        try {
            storage.delete(key);
            return true;
        } catch (RuntimeException e) {
            log.warn(
                    "Product cover object could not be deleted (product {}): {}",
                    productId,
                    e.getClass().getName());
            return false;
        }
    }

    private void forgetBestEffort(String key, UUID productId) {
        try {
            pending.remove(key);
        } catch (RuntimeException e) {
            // the row stays; the recurring task finds the object already gone and clears it
            log.warn(
                    "Product cover pending record could not be cleared (product {}): {}",
                    productId,
                    e.getClass().getName());
        }
    }

    private static void requireNoTransaction() {
        // Deleting "after commit" and holding no row lock during network calls both assume that this service owns the
        // transaction. Inside a caller's transaction nothing would commit yet, so refuse.
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(), "must not run in a transaction");
    }

    private static Path writeTemp(byte[] bytes) {
        Path file;
        try {
            file = Files.createTempFile("cover-", ".bin");
        } catch (IOException e) {
            throw new ProductFileStorageException("the temporary file cannot be written");
        }
        try {
            return Files.write(file, bytes);
        } catch (IOException | RuntimeException e) {
            // the caller's finally starts only after this method returns, so the half-written file goes here
            deleteQuietly(file);
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ProductFileStorageException("the temporary file cannot be written");
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // a temp file left behind is harmless
        }
    }

    /** First 8 bytes of the SHA-256 of the stored bytes, as 16 hex characters: changes whenever the image does. */
    private static String version(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AuditEntry entry(
            AuditAction action, AuditResult result, UUID administratorId, UUID productId, String origin) {
        return new AuditEntry(action, result, administratorId, AuditObject.PRODUCT, productId, origin);
    }
}
