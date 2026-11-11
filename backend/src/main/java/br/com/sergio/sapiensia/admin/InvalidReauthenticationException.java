package br.com.sergio.sapiensia.admin;

/**
 * A wrong password, or a wrong, malformed or already used code, in a reauthentication; answered with 400
 * {@code {errors: {credentials: "CREDENTIALS_INVALID"}}}, the same for every cause.
 */
public class InvalidReauthenticationException extends RuntimeException {

    public InvalidReauthenticationException() {
        // no message: nothing about the typed password or code
        super(null, null, false, false);
    }
}
