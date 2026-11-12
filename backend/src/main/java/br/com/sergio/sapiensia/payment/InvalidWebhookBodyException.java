package br.com.sergio.sapiensia.payment;

/** The webhook body is not a JSON event with a valid id and type. Carries no part of the body. */
public final class InvalidWebhookBodyException extends RuntimeException {

    public InvalidWebhookBodyException() {
        super("invalid webhook body");
    }
}
