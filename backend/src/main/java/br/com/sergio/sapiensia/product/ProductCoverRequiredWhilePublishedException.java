package br.com.sergio.sapiensia.product;

/** The removal of the Capa of a PUBLISHED Product was asked (FR-011); the API answers 409. No message. */
public class ProductCoverRequiredWhilePublishedException extends RuntimeException {

    ProductCoverRequiredWhilePublishedException() {
        super(null, null, false, false);
    }
}
