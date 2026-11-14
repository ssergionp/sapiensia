package br.com.sergio.sapiensia.payment;

/** No Order of this Customer has this id; the API answers 404 with no body. */
public class OrderNotFoundException extends RuntimeException {

    OrderNotFoundException() {
        super(null, null, false, false);
    }
}
