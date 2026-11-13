package br.com.sergio.sapiensia.product;

import java.util.UUID;

/** One Product in the public Catalog list (FR-016): only public fields; coverVersion is null without a cover. */
record CatalogItem(
        UUID id, String title, String summary, ProductCategory category, int priceCents, String coverVersion) {}
