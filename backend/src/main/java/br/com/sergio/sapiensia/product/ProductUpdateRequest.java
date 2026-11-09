package br.com.sergio.sapiensia.product;

import jakarta.validation.constraints.NotNull;

/**
 * The body of a text edit (FR-016): title, description, category and the version the client read. There is no price
 * here on purpose: changing it is a separate capability and route (SEC-029), so a {@code priceCents} sent in this body
 * is ignored like any other extra field (id, status, createdAt).
 */
record ProductUpdateRequest(
        @ProductTitle String title,
        @ProductDescription String description,
        @NotNull(message = "CATEGORY_REQUIRED") ProductCategory category,
        @NotNull(message = "VERSION_REQUIRED") Integer version) {

    ProductUpdateRequest {
        title = ProductText.clean(title);
        description = ProductText.clean(description);
    }
}
