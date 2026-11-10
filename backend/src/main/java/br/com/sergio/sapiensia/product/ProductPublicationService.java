package br.com.sergio.sapiensia.product;

import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes and unpublishes Products. Both take the Product row lock that the file swap also takes, so the two are
 * queued: publishing always sees a file that is being saved, and a swap always sees a publication that just happened.
 * Asking for the state the Product already has is a no-op with no audit event. Nothing here logs file data.
 */
@Service
class ProductPublicationService {

    private final Products products;
    private final ProductFiles files;
    private final AuditLog audit;

    ProductPublicationService(Products products, ProductFiles files, AuditLog audit) {
        this.products = products;
        this.files = files;
        this.audit = audit;
    }

    /** Needs a file: without one, the FAILURE event is written in its own transaction and the caller answers 409. */
    @Transactional
    ProductResponse publish(UUID id, UUID administratorId, String origin) {
        lock(id);
        if (files.currentKey(id).isEmpty()) {
            audit.recordIndependent(
                    entry(AuditAction.ADMIN_PRODUCT_PUBLISHED, AuditResult.FAILURE, administratorId, id, origin));
            throw new ProductFileRequiredException();
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
