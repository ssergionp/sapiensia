package br.com.sergio.sapiensia.payment;

/** The Customer's Access to this Product is suspended by a Refund request, so it cannot be bought again yet; 409. */
public class RefundInProgressException extends RuntimeException {

    RefundInProgressException() {
        super(null, null, false, false);
    }
}
