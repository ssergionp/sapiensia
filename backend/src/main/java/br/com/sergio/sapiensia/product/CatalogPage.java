package br.com.sergio.sapiensia.product;

import java.util.List;

/** A page of the public Catalog: 20 items per page, newest first publication first. */
record CatalogPage(List<CatalogItem> items, int page, int totalPages) {}
