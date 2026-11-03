package br.com.sergio.sapiensia.customer;

/** The confirmation link is unknown, expired, already used or out of password attempts (all look the same). */
public class InvalidConfirmationTokenException extends RuntimeException {

    public InvalidConfirmationTokenException() {
        super("Invalid confirmation token");
    }
}
