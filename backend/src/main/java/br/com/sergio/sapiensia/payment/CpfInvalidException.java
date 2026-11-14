package br.com.sergio.sapiensia.payment;

/** The CPF is not valid, or the provider refused it. No message: the CPF must never travel in an error. */
public class CpfInvalidException extends RuntimeException {

    CpfInvalidException() {
        super(null, null, false, false);
    }
}
