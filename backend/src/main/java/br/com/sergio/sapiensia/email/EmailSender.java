package br.com.sergio.sapiensia.email;

/** Sends one plain-text e-mail. Any failure is reported as an {@link EmailDeliveryException}. */
public interface EmailSender {

    void send(EmailMessage message);
}
