package br.com.sergio.sapiensia.product;

/** The database has a cover but the storage has no such object. No message: nothing about the key may leak. */
public class ProductCoverMissingException extends RuntimeException {

    ProductCoverMissingException() {
        super(null, null, false, false);
    }
}
