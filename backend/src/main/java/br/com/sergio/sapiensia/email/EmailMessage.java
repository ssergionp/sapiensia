package br.com.sergio.sapiensia.email;

/** An e-mail to send. {@code idempotencyKey} is optional (null): the sender passes it on when the provider supports it. */
public record EmailMessage(String to, String subject, String text, String idempotencyKey) {

    public EmailMessage(String to, String subject, String text) {
        this(to, subject, text, null);
    }
}
