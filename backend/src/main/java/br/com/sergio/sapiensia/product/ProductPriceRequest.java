package br.com.sergio.sapiensia.product;

import jakarta.validation.constraints.NotNull;

/** The body of a price change (SEC-029): the new price in cents and the version the client read. */
record ProductPriceRequest(
        @ProductPrice Integer priceCents,
        @NotNull(message = "VERSION_REQUIRED") Integer version) {}
