package br.com.sergio.sapiensia.product;

import java.time.Instant;
import java.util.UUID;

/** A Product as a row of the list: the same as {@link ProductResponse} but without the (up to 5000 characters) description. */
record ProductListItem(
        UUID id,
        String title,
        ProductCategory category,
        int priceCents,
        ProductStatus status,
        int version,
        Instant createdAt,
        Instant updatedAt) {}
