package br.com.sergio.sapiensia.product;

/** Why a product file was refused; the name is the error code the API returns. */
public enum ProductFileRejection {
    FILE_REQUIRED,
    FILE_EMPTY,
    FILE_TOO_LARGE,
    FILE_NOT_PDF,
    FILE_INVALID,
    FILE_ENCRYPTED,
    FILE_TOO_MANY_PAGES,
    PUBLISHED_BEFORE
}
