package br.com.sergio.sapiensia.product;

import java.util.List;

/** One page of the list (SEC-015): the size is fixed in {@link Products#PAGE_SIZE}, so there is no size to abuse. */
record ProductPage(List<ProductListItem> items, int page, int totalPages) {}
