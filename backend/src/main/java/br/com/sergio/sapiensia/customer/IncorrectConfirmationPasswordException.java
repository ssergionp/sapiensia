package br.com.sergio.sapiensia.customer;

/** The link is valid but the password does not match the account; the link stays usable. */
public class IncorrectConfirmationPasswordException extends RuntimeException {

    public IncorrectConfirmationPasswordException() {
        super("Incorrect password for e-mail confirmation");
    }
}
