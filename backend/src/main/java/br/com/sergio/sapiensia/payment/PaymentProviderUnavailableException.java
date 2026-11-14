package br.com.sergio.sapiensia.payment;

/** The payment provider failed or did not answer in time. */
public class PaymentProviderUnavailableException extends RuntimeException {

    PaymentProviderUnavailableException() {
        super(null, null, false, false);
    }
}
