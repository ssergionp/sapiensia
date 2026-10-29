package br.com.sergio.sapiensia.customer;

public class PasswordCompromisedException extends RuntimeException {

    public PasswordCompromisedException() {
        super("Password appears in a known data breach");
    }
}
