package br.com.sergio.sapiensia.product;

/** Result of validating an uploaded cover image. */
public sealed interface CoverCheck {

    /** {@code cleaned} is the image without metadata: the bytes to store and serve. */
    record Valid(CoverFormat format, int width, int height, byte[] cleaned) implements CoverCheck {}

    record Rejected(ProductCoverRejection reason) implements CoverCheck {}
}
