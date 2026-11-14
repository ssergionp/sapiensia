package br.com.sergio.sapiensia.payment;

/** The Customer has no payment account yet and sent no CPF. */
public class CpfRequiredException extends RuntimeException {

    CpfRequiredException() {
        super(null, null, false, false);
    }
}
