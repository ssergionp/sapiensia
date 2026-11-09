package br.com.sergio.sapiensia.product;

/** The Product changed since the client read it (stale version); the API answers 409. No message. */
public class ProductVersionConflictException extends RuntimeException {

    ProductVersionConflictException() {
        super(null, null, false, false);
    }
}
