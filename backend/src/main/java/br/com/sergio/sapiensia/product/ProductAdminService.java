package br.com.sergio.sapiensia.product;

import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What the Administrator does with Products. Nothing here logs a title, a description or a price. */
@Service
class ProductAdminService {

    private final Products products;
    private final AuditLog audit;

    ProductAdminService(Products products, AuditLog audit) {
        this.products = products;
        this.audit = audit;
    }

    /** Creates the Product and its SUCCESS event in one transaction: no trail, no Product (the caller answers 503). */
    @Transactional
    ProductResponse create(ProductRequest request, UUID administratorId, String origin) {
        ProductResponse created = products.insert(request);
        audit.recordInTransaction(new AuditEntry(
                AuditAction.ADMIN_PRODUCT_CREATED,
                AuditResult.SUCCESS,
                administratorId,
                AuditObject.PRODUCT,
                created.id(),
                origin));
        return created;
    }

    /**
     * Edits title, description and category under the optimistic lock. SUCCESS is written in the same transaction (no
     * trail, no change); a stale version writes FAILURE in its own transaction, which survives the rollback.
     */
    @Transactional
    ProductResponse update(UUID id, ProductUpdateRequest request, UUID administratorId, String origin) {
        return change(id, products.update(id, request), AuditAction.ADMIN_PRODUCT_UPDATED, administratorId, origin);
    }

    /** Changes the price under the same lock and the same audit rules, with its own action (SEC-029). */
    @Transactional
    ProductResponse changePrice(UUID id, ProductPriceRequest request, UUID administratorId, String origin) {
        return change(
                id,
                products.updatePrice(id, request),
                AuditAction.ADMIN_PRODUCT_PRICE_CHANGED,
                administratorId,
                origin);
    }

    private ProductResponse change(UUID id, int rows, AuditAction action, UUID administratorId, String origin) {
        if (rows == 0) {
            if (!products.exists(id)) {
                throw new ProductNotFoundException();
            }
            audit.recordIndependent(entry(action, AuditResult.FAILURE, administratorId, id, origin));
            throw new ProductVersionConflictException();
        }
        audit.recordInTransaction(entry(action, AuditResult.SUCCESS, administratorId, id, origin));
        return products.find(id).orElseThrow(ProductNotFoundException::new);
    }

    private static AuditEntry entry(
            AuditAction action, AuditResult result, UUID administratorId, UUID id, String origin) {
        return new AuditEntry(action, result, administratorId, AuditObject.PRODUCT, id, origin);
    }

    @Transactional(readOnly = true)
    ProductResponse get(UUID id) {
        return products.find(id).orElseThrow(ProductNotFoundException::new);
    }

    @Transactional(readOnly = true)
    ProductPage list(int page) {
        long total = products.count();
        int totalPages = (int) ((total + Products.PAGE_SIZE - 1) / Products.PAGE_SIZE);
        return new ProductPage(products.list(page), page, totalPages);
    }
}
