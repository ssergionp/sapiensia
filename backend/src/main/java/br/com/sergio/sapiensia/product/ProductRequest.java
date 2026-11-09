package br.com.sergio.sapiensia.product;

import jakarta.validation.constraints.NotNull;

/**
 * The body of a new Product (FR-016, FR-018). Text is normalized to NFC and stripped before it is validated and stored.
 * The message of each constraint is the error code the screen turns into text. Extra JSON fields (id, status, version)
 * are not part of the record, so they are ignored (SEC-012). The price is in cents, within the commercial range of
 * ADR-0007; the database only keeps {@code price_cents > 0}. The rules of title, description and price live in
 * {@link ProductTitle}, {@link ProductDescription} and {@link ProductPrice}, shared with the edit bodies.
 */
record ProductRequest(
        @ProductTitle String title,
        @ProductDescription String description,
        @NotNull(message = "CATEGORY_REQUIRED") ProductCategory category,
        @ProductPrice Integer priceCents) {

    ProductRequest {
        title = ProductText.clean(title);
        description = ProductText.clean(description);
    }
}
