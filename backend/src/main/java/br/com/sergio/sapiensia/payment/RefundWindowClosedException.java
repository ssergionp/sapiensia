package br.com.sergio.sapiensia.payment;

/** The Refund window of the Order is over (FR-023); the API answers 409. */
public class RefundWindowClosedException extends RuntimeException {

    RefundWindowClosedException() {
        super(null, null, false, false);
    }
}
