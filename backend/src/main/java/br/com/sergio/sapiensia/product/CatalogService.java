package br.com.sergio.sapiensia.product;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public reading of the Catalog: published Products only, the filter is in the SQL. */
@Service
public class CatalogService {

    private final Products products;

    CatalogService(Products products) {
        this.products = products;
    }

    @Transactional(readOnly = true)
    CatalogPage list(int page) {
        List<CatalogItem> items = products.listPublished(page);
        long total = products.countPublished();
        int totalPages = (int) ((total + Products.PAGE_SIZE - 1) / Products.PAGE_SIZE);
        return new CatalogPage(items, page, totalPages);
    }

    /** A draft, an unpublished and a missing Product are the same {@link ProductNotFoundException} (SEC-010). */
    @Transactional(readOnly = true)
    public CatalogProduct get(UUID id) {
        return products.findPublished(id).orElseThrow(ProductNotFoundException::new);
    }
}
