package br.com.sergio.sapiensia.payment;

/** Another request of this Customer is creating an Order. */
public class OrderInProgressException extends RuntimeException {

    OrderInProgressException() {
        super(null, null, false, false);
    }
}
