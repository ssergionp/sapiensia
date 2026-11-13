package br.com.sergio.sapiensia.product;

import java.time.Instant;
import java.util.UUID;

/**
 * A Product as the panel sees it. The first publication date is null until then. The version is what a later edit sends
 * back (optimistic lock).
 */
record ProductResponse(
        UUID id,
        String title,
        String description,
        ProductCategory category,
        int priceCents,
        ProductStatus status,
        int version,
        Instant createdAt,
        Instant updatedAt,
        ProductFileInfo file,
        ProductCoverInfo cover,
        Instant firstPublishedAt) {}
