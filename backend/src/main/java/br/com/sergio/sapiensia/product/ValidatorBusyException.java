package br.com.sergio.sapiensia.product;

/** Another PDF validation is already running; only one runs at a time (SEC-047). */
public class ValidatorBusyException extends RuntimeException {

    public ValidatorBusyException() {
        super("A PDF validation is already running");
    }
}
