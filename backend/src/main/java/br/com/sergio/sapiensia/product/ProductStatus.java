package br.com.sergio.sapiensia.product;

/**
 * Where a Product is in its life. DRAFT exists only before the first publication; a Product never goes back to it
 * (the database enforces it through first_published_at).
 */
public enum ProductStatus {
    DRAFT,
    PUBLISHED,
    UNPUBLISHED
}
