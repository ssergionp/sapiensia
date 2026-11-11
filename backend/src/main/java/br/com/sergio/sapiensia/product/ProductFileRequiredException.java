package br.com.sergio.sapiensia.product;

/** Publishing was asked for a Product that has no file yet (FR-011); the API answers 409. No message. */
public class ProductFileRequiredException extends RuntimeException {

    ProductFileRequiredException() {
        super(null, null, false, false);
    }
}
