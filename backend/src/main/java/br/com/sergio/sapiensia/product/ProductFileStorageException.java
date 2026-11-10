package br.com.sergio.sapiensia.product;

/** A storage failure. The message never carries a response body, a URL, a path or a secret, and has no cause. */
class ProductFileStorageException extends RuntimeException {

    ProductFileStorageException(String message) {
        super(message);
    }
}
