package br.com.sergio.sapiensia.product;

/** No Product has this id; the API answers 404. No message: nothing about the request. */
public class ProductNotFoundException extends RuntimeException {

    ProductNotFoundException() {
        super(null, null, false, false);
    }
}
