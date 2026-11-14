package br.com.sergio.sapiensia.payment;

/** The Customer already has the Access to this Product (FR-024). */
public class ProductAlreadyOwnedException extends RuntimeException {

    ProductAlreadyOwnedException() {
        super(null, null, false, false);
    }
}
