package br.com.sergio.sapiensia.payment;

/** The Order is not paid (pending, expired or already refunded), so no Refund can be asked (FR-023); 409. */
public class RefundNotAvailableException extends RuntimeException {

    RefundNotAvailableException() {
        super(null, null, false, false);
    }
}
