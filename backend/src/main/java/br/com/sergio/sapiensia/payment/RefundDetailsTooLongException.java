package br.com.sergio.sapiensia.payment;

/** The comment of a Refund request is over 500 characters (SEC-012); 400, never echoing the text (SEC-036). */
public class RefundDetailsTooLongException extends RuntimeException {

    RefundDetailsTooLongException() {
        super(null, null, false, false);
    }
}
