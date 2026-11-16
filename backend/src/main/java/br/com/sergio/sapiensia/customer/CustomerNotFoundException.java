package br.com.sergio.sapiensia.customer;

/** No account has this e-mail, or it is already anonymized; the API answers 404 CUSTOMER_NOT_FOUND. */
public class CustomerNotFoundException extends RuntimeException {

    CustomerNotFoundException() {
        super(null, null, false, false);
    }
}
