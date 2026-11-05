package br.com.sergio.sapiensia.customer;

import br.com.sergio.sapiensia.email.EmailMessage;
import br.com.sergio.sapiensia.email.EmailSender;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Builds and sends the customer e-mails. Runs inside the background tasks of {@link CustomerEmailTasks}. */
@Service
class CustomerEmailService {

    private static final String CONFIRMATION_SUBJECT = "Confirme seu e-mail na SapiensIA";
    private static final String CONFIRMATION_TEXT = """
            Olá!

            Para concluir seu cadastro na SapiensIA, confirme seu e-mail pelo link abaixo. Você vai precisar digitar \
            a senha que escolheu no cadastro. O link vale por 24 horas e só pode ser usado uma vez.

            %s

            Se você se cadastrou mais de uma vez com este e-mail, vale a senha e o link do cadastro mais recente.

            Se você não criou uma conta na SapiensIA, ignore esta mensagem.
            """;

    private static final String NOTICE_SUBJECT = "Tentativa de cadastro na SapiensIA";
    private static final String NOTICE_TEXT = """
            Olá!

            Alguém tentou criar uma conta na SapiensIA com este e-mail, que já tem cadastro.

            Se foi você, não precisa fazer nada: use a conta que você já tem.

            Se não foi você, ignore esta mensagem. Nada foi alterado na sua conta.
            """;

    private static final String PASSWORD_RESET_SUBJECT = "Redefinição de senha na SapiensIA";
    private static final String PASSWORD_RESET_TEXT = """
            Olá!

            Recebemos um pedido para redefinir a senha da sua conta na SapiensIA. Use o link abaixo para escolher \
            uma nova senha. O link vale por 30 minutos e só pode ser usado uma vez.

            %s

            Se você não pediu isso, ignore esta mensagem: sua senha continua a mesma.
            """;

    private static final String PASSWORD_CHANGED_SUBJECT = "Sua senha na SapiensIA foi alterada";
    private static final String PASSWORD_CHANGED_TEXT = """
            Olá!

            A senha da sua conta na SapiensIA acabou de ser alterada, e todos os aparelhos foram desconectados.

            Se foi você, não precisa fazer nada.

            Se não foi você, use "Esqueci minha senha" para criar outra senha agora.
            """;

    private static final String NEW_DEVICE_SUBJECT = "Novo acesso à sua conta na SapiensIA";
    private static final String NEW_DEVICE_TEXT = """
            Olá!

            Sua conta na SapiensIA foi acessada em %s (horário de Brasília), em um aparelho ou navegador que ainda não \
            tínhamos visto.

            Se foi você, não precisa fazer nada.

            Se não foi você, use "Esqueci minha senha" para criar outra senha agora. A troca de senha desconecta todos \
            os aparelhos.
            """;

    private static final DateTimeFormatter NEW_DEVICE_DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm").withZone(ZoneId.of("America/Sao_Paulo"));

    private final CustomerRepository customers;
    private final EmailConfirmationTokens confirmationTokens;
    private final PasswordResetTokens passwordResetTokens;
    private final EmailSender sender;
    private final String publicBaseUrl;

    CustomerEmailService(
            CustomerRepository customers,
            EmailConfirmationTokens confirmationTokens,
            PasswordResetTokens passwordResetTokens,
            EmailSender sender,
            @Value("${app.public-base-url}") String publicBaseUrl) {
        this.customers = customers;
        this.confirmationTokens = confirmationTokens;
        this.passwordResetTokens = passwordResetTokens;
        this.sender = sender;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", ""); // links append "/path"
        if (this.publicBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "APP_PUBLIC_BASE_URL (app.public-base-url) must be set: e-mail links would be relative without it");
        }
    }

    /** Sends a new confirmation link, unless the customer no longer exists or is already confirmed. */
    void sendConfirmation(UUID customerId) {
        customers.findUnconfirmedEmailById(customerId).ifPresent(email -> {
            String link = publicBaseUrl + "/confirmar-email#" + confirmationTokens.issue(customerId);
            sender.send(new EmailMessage(email, CONFIRMATION_SUBJECT, CONFIRMATION_TEXT.formatted(link)));
        });
    }

    /**
     * Replaces the customer's unused reset links with a new one and sends it, unless the customer does not exist or
     * is not confirmed (then nothing is stored and nothing is sent).
     */
    void sendPasswordReset(UUID customerId) {
        customers.findConfirmedEmailById(customerId).ifPresent(email -> {
            String link = publicBaseUrl + "/redefinir-senha#" + passwordResetTokens.reissue(customerId);
            sender.send(new EmailMessage(email, PASSWORD_RESET_SUBJECT, PASSWORD_RESET_TEXT.formatted(link)));
        });
    }

    /** Tells the owner that the password changed; carries no password, token or link. */
    void sendPasswordChangedNotice(UUID customerId) {
        customers
                .findEmailById(customerId)
                .ifPresent(
                        email -> sender.send(new EmailMessage(email, PASSWORD_CHANGED_SUBJECT, PASSWORD_CHANGED_TEXT)));
    }

    /** Tells the owner that the account was opened from a new device; carries only the date, no token or password. */
    void sendNewDeviceNotice(NewDeviceNotice notice) {
        customers.findEmailById(notice.customerId()).ifPresent(email -> {
            String when = NEW_DEVICE_DATE.format(notice.loggedInAt());
            sender.send(new EmailMessage(email, NEW_DEVICE_SUBJECT, NEW_DEVICE_TEXT.formatted(when)));
        });
    }

    /** Tells the owner of a confirmed account that someone tried to register with it; carries no token. */
    void sendAccountExistsNotice(UUID customerId) {
        customers
                .findEmailById(customerId)
                .ifPresent(email -> sender.send(new EmailMessage(email, NOTICE_SUBJECT, NOTICE_TEXT)));
    }
}
