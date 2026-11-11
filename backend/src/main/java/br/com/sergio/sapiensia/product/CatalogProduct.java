package br.com.sergio.sapiensia.product;

import java.util.UUID;

/** A published Product as the public Catalog shows it (FR-016): nothing about its state, dates or file. */
record CatalogProduct(UUID id, String title, String description, ProductCategory category, int priceCents) {}
