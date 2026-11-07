package br.com.sergio.sapiensia.admin;

/** A wrong, malformed or already used TOTP code; answered with 400 {@code {errors: {code: "CODE_INVALID"}}}. */
public class InvalidMfaCodeException extends RuntimeException {

    public InvalidMfaCodeException() {
        // no message: nothing about the typed code
        super(null, null, false, false);
    }
}
