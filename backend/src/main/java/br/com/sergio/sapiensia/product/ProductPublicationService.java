package br.com.sergio.sapiensia.product;

import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes and unpublishes Products. Publishing needs a file and a Capa. Publish and unpublish both take the Product
 * row lock that the file swap also takes, so the two are queued: publishing always sees a file that is being saved,
 * and a swap always sees a publication that just happened.
 * Asking for the state the Product already has is a no-op with no audit event, but only when the requirements are met:
 * publishing again without a Capa is refused (COVER_REQUIRED, with a FAILURE audit event) even when the Product is
 * already PUBLISHED. Nothing here logs file data.
 */
@Service
class ProductPublicationService {

    private final Products products;
    private final ProductFiles files;
    private final ProductCovers covers;
    private final AuditLog audit;

    ProductPublicationService(Products products, ProductFiles files, ProductCovers covers, AuditLog audit) {
        this.products = products;
        this.files = files;
        this.covers = covers;
        this.audit = audit;
    }

    /**
     * Needs a file and a Capa: without either, the FAILURE event is written in its own transaction and the caller
     * answers 409.
     */
    @Transactional
    ProductResponse publish(UUID id, UUID administratorId, String origin) {
        lock(id);
        Map<String, String> missing = new LinkedHashMap<>();
        if (files.currentKey(id).isEmpty()) {
            missing.put("file", "FILE_REQUIRED");
        }
        if (covers.currentKey(id).isEmpty()) {
            missing.put("cover", "COVER_REQUIRED");
        }
        if (!missing.isEmpty()) {
            audit.recordIndependent(
                    entry(AuditAction.ADMIN_PRODUCT_PUBLISHED, AuditResult.FAILURE, administratorId, id, origin));
            throw new ProductNotPublishableException(missing);
        }
        return change(products.publish(id), id, AuditAction.ADMIN_PRODUCT_PUBLISHED, administratorId, origin);
    }

    @Transactional
    ProductResponse unpublish(UUID id, UUID administratorId, String origin) {
        lock(id);
        return change(products.unpublish(id), id, AuditAction.ADMIN_PRODUCT_UNPUBLISHED, administratorId, origin);
    }

    private void lock(UUID id) {
        if (!files.lockProduct(id)) {
            throw new ProductNotFoundException();
        }
    }

    /** Under the lock, 0 rows means the Product was already in the requested state: answer, but audit nothing. */
    private ProductResponse change(int rows, UUID id, AuditAction action, UUID administratorId, String origin) {
        if (rows > 0) {
            audit.recordInTransaction(entry(action, AuditResult.SUCCESS, administratorId, id, origin));
        }
        return products.find(id).orElseThrow(ProductNotFoundException::new);
    }

    private static AuditEntry entry(
            AuditAction action, AuditResult result, UUID administratorId, UUID id, String origin) {
        return new AuditEntry(action, result, administratorId, AuditObject.PRODUCT, id, origin);
    }
}
