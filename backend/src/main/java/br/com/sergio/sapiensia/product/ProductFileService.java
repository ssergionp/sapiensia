package br.com.sergio.sapiensia.product;

import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * Replaces the PDF of a Product. The row lock is never held while talking to the storage: validate, upload under a new
 * key, then a short transaction swaps the pointer, and only after the commit is the old object deleted. A failure in
 * the middle leaves at most an orphan object, never a Product pointing at a missing one. The temporary file belongs
 * to the caller. Nothing here logs a file name, key, size or content.
 */
@Service
class ProductFileService {

    private static final Logger log = LoggerFactory.getLogger(ProductFileService.class);

    private record Swapped(Optional<String> oldKey, ProductResponse product) {}

    private final PdfFileValidator validator;
    private final ProductFileStorage storage;
    private final ProductFiles files;
    private final Products products;
    private final AuditLog audit;
    private final TransactionTemplate transaction;

    ProductFileService(
            PdfFileValidator validator,
            ProductFileStorage storage,
            ProductFiles files,
            Products products,
            AuditLog audit,
            PlatformTransactionManager transactionManager) {
        this.validator = validator;
        this.storage = storage;
        this.files = files;
        this.products = products;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    ProductResponse upload(
            UUID productId,
            Path tempFile,
            String submittedName,
            String declaredContentType,
            UUID administratorId,
            String origin) {
        // Deleting the old object "after commit" and holding no row lock during network calls both assume that this
        // method owns the transaction. Inside a caller's transaction the swap would not commit yet, so refuse.
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(), "must not run in a transaction");
        if (!products.exists(productId)) {
            throw new ProductNotFoundException();
        }
        // Cheap pre-check so no byte reaches the storage for nothing; the one that counts is under the lock, in store.
        // Ticket #22 replaces both with a re-authentication requirement.
        if (products.everPublished(productId)) {
            audit.recordIndependent(entry(AuditResult.FAILURE, administratorId, productId, origin));
            throw new ProductFileRejectedException(ProductFileRejection.PUBLISHED_BEFORE);
        }
        PdfCheck check = validator.check(tempFile, submittedName, declaredContentType);
        if (check instanceof PdfCheck.Rejected rejected) {
            audit.recordIndependent(entry(AuditResult.FAILURE, administratorId, productId, origin));
            throw new ProductFileRejectedException(rejected.reason());
        }
        return store(productId, tempFile, ((PdfCheck.Valid) check).pageCount(), administratorId, origin);
    }

    private ProductResponse store(UUID productId, Path tempFile, int pageCount, UUID administratorId, String origin) {
        long size = sizeOf(tempFile);
        String newKey = ProductFileKeys.newKey(productId);
        storage.put(newKey, tempFile, size);
        Swapped swapped;
        try {
            swapped = transaction.execute(status -> {
                if (!files.lockProduct(productId)) {
                    throw new ProductNotFoundException();
                }
                // Publishing takes this same lock, so the marker read here cannot change before the swap commits.
                if (products.everPublished(productId)) {
                    throw new ProductFileRejectedException(ProductFileRejection.PUBLISHED_BEFORE);
                }
                Optional<String> previous = files.currentKey(productId);
                files.swap(productId, newKey, size, pageCount);
                audit.recordInTransaction(entry(AuditResult.SUCCESS, administratorId, productId, origin));
                return new Swapped(previous, products.find(productId).orElseThrow(ProductNotFoundException::new));
            });
        } catch (RuntimeException e) {
            // Nothing was committed (or the commit failed): the old row still rules, so drop the new object.
            // Accepted residual risk: if the commit throws after the database really committed (e.g. the connection
            // drops on the reply), this deletes the object the row now points to. Rare; accepted for this ticket.
            deleteBestEffort(newKey, productId);
            if (e instanceof ProductFileRejectedException) {
                // The transaction rolled back, so the FAILURE has to be written outside of it.
                audit.recordIndependent(entry(AuditResult.FAILURE, administratorId, productId, origin));
            }
            throw e;
        }
        swapped.oldKey().ifPresent(key -> deleteBestEffort(key, productId));
        return swapped.product();
    }

    /** A failed delete only leaves an orphan, which is never delivered; it must not change the outcome. */
    private void deleteBestEffort(String key, UUID productId) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn(
                    "Product file object could not be deleted (product {}): {}",
                    productId,
                    e.getClass().getName());
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new ProductFileStorageException("the temporary file cannot be read");
        }
    }

    private static AuditEntry entry(AuditResult result, UUID administratorId, UUID productId, String origin) {
        return new AuditEntry(
                AuditAction.ADMIN_PRODUCT_FILE_UPLOADED,
                result,
                administratorId,
                AuditObject.PRODUCT,
                productId,
                origin);
    }
}
