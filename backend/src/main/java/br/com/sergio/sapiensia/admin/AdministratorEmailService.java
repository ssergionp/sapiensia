package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.email.EmailMessage;
import br.com.sergio.sapiensia.email.EmailSender;
import br.com.sergio.sapiensia.security.Tokens;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds and sends the first-access e-mail. The link uses only {@code app.public-base-url}: it runs in a background
 * task, without a request.
 */
@Service
class AdministratorEmailService {

    private static final String SUBJECT = "Defina sua senha de Administrador na SapiensIA";
    private static final String TEXT = """
            Olá!

            Uma conta de Administrador da SapiensIA foi criada para este e-mail. Use o link abaixo para definir a \
            sua senha. O link vale por %s e só pode ser usado uma vez.

            %s

            Se você não esperava esta mensagem, não use o link.
            """;

    private final Administrators administrators;
    private final AdministratorPasswordTokens tokens;
    private final EmailSender sender;
    private final String publicBaseUrl;

    AdministratorEmailService(
            Administrators administrators,
            AdministratorPasswordTokens tokens,
            EmailSender sender,
            @Value("${app.public-base-url}") String publicBaseUrl) {
        this.administrators = administrators;
        this.tokens = tokens;
        this.sender = sender;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", ""); // links append "/path"
        if (this.publicBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "APP_PUBLIC_BASE_URL (app.public-base-url) must be set: e-mail links would be relative without it");
        }
    }

    /**
     * Sends a new set-password link and only then stores its hash (replacing the unused ones). If the send fails,
     * nothing is stored, so the database never holds a link that was not delivered. The task carries no id: the
     * Administrator is looked up when it runs, so a row deleted and recreated while a task was pending still gets its
     * link. Does nothing if no Administrator is waiting for a password.
     */
    void sendPasswordSetup() {
        administrators.findWithoutPassword().ifPresent(pending -> {
            String token = Tokens.newToken();
            String link = publicBaseUrl + "/painel/definir-senha#" + token;
            sender.send(new EmailMessage(pending.email(), SUBJECT, text(link, tokens.ttl())));
            tokens.replaceWith(pending.id(), Tokens.hash(token));
        });
    }

    /** The e-mail body; the validity comes from the same TTL that sets the link's expiry. */
    static String text(String link, Duration ttl) {
        return TEXT.formatted(validity(ttl), link);
    }

    /** Rounded up to whole minutes, so the text never promises more time than the link has. */
    private static String validity(Duration ttl) {
        long minutes = ttl.plusMinutes(1).minusNanos(1).toMinutes();
        if (minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours == 1 ? "1 hora" : hours + " horas";
        }
        return minutes == 1 ? "1 minuto" : minutes + " minutos";
    }
}
