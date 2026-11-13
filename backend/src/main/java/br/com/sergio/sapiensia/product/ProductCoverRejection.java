package br.com.sergio.sapiensia.product;

/** Why a product cover was refused; the name is the error code the API returns. */
public enum ProductCoverRejection {
    COVER_REQUIRED,
    COVER_EMPTY,
    COVER_TOO_LARGE,
    COVER_NOT_IMAGE,
    COVER_INVALID,
    COVER_TOO_MANY_PIXELS,
    COVER_ANIMATED
}
