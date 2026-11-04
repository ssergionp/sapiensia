package br.com.sergio.sapiensia.customer;

public class PasswordCompromisedException extends RuntimeException {

    private final String field;

    public PasswordCompromisedException() {
        this("password");
    }

    /** @param field name of the request field that carried the password, reported back in the error */
    public PasswordCompromisedException(String field) {
        super("Password appears in a known data breach");
        this.field = field;
    }

    public String field() {
        return field;
    }
}
