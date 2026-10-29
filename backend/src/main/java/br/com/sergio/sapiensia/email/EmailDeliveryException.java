package br.com.sergio.sapiensia.email;

/** The message must never carry the recipient, the provider response body or the API key. */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message) {
        super(message);
    }

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
