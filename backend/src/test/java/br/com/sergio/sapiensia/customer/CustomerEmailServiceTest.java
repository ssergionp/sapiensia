package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.sergio.sapiensia.email.EmailMessage;
import br.com.sergio.sapiensia.email.EmailSender;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class CustomerEmailServiceTest {

    private final UUID id = UUID.randomUUID();
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final EmailConfirmationTokens tokens = mock(EmailConfirmationTokens.class);
    private final PasswordResetTokens resetTokens = mock(PasswordResetTokens.class);
    private final EmailSender sender = mock(EmailSender.class);

    private EmailMessage sentConfirmationFor(String baseUrl) {
        when(customers.findUnconfirmedEmailById(id)).thenReturn(Optional.of("a@example.com"));
        when(tokens.issue(id)).thenReturn("tok");
        new CustomerEmailService(customers, tokens, resetTokens, sender, baseUrl).sendConfirmation(id);
        return captured();
    }

    private EmailMessage captured() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(sender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void shouldBuildTheConfirmationLinkWithoutDoubleSlashWhenBaseUrlEndsWithSlash() {
        assertThat(sentConfirmationFor("http://localhost:5173/").text())
                .contains("http://localhost:5173/confirmar-email#tok")
                .doesNotContain("5173//");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "/"})
    void shouldRefuseToStartWhenTheBaseUrlIsBlank(String baseUrl) {
        assertThatThrownBy(() -> new CustomerEmailService(customers, tokens, resetTokens, sender, baseUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_PUBLIC_BASE_URL");
    }

    @Test
    void shouldKeepTheLinksUnchangedWhenBaseUrlHasNoTrailingSlash() {
        assertThat(sentConfirmationFor("http://localhost:5173").text())
                .contains("http://localhost:5173/confirmar-email#tok");
    }

    @Test
    @DisplayName("SEC-007: o link de redefinição vai por e-mail, numa linha só")
    void shouldBuildThePasswordResetLink() {
        when(customers.findConfirmedEmailById(id)).thenReturn(Optional.of("a@example.com"));
        when(resetTokens.reissue(id)).thenReturn("tok");

        new CustomerEmailService(customers, tokens, resetTokens, sender, "http://localhost:5173/")
                .sendPasswordReset(id);

        EmailMessage message = captured();
        assertThat(message.to()).isEqualTo("a@example.com");
        assertThat(message.subject()).isEqualTo("Redefinição de senha na SapiensIA");
        assertThat(message.text()).contains("\nhttp://localhost:5173/redefinir-senha#tok\n");
    }

    @Test
    @DisplayName("SEC-007: o aviso de aparelho novo mostra a data no horário de Brasília e nada mais")
    void shouldFormatTheNewDeviceNoticeInBrasiliaTime() {
        when(customers.findEmailById(id)).thenReturn(Optional.of("a@example.com"));

        new CustomerEmailService(customers, tokens, resetTokens, sender, "http://localhost:5173")
                .sendNewDeviceNotice(new NewDeviceNotice(id, Instant.parse("2026-11-04T17:32:00Z")));

        EmailMessage message = captured();
        assertThat(message.to()).isEqualTo("a@example.com");
        assertThat(message.subject()).isEqualTo("Novo acesso à sua conta na SapiensIA");
        assertThat(message.text()).contains("acessada em 04/11/2026 às 14:32 (horário de Brasília)");
        assertThat(message.text()).doesNotContain("http");
    }

    @Test
    @DisplayName("SEC-007: o aviso de aparelho novo não envia nada para conta inexistente")
    void shouldSendNoNewDeviceNoticeForAMissingCustomer() {
        when(customers.findEmailById(id)).thenReturn(Optional.empty());

        new CustomerEmailService(customers, tokens, resetTokens, sender, "http://localhost:5173")
                .sendNewDeviceNotice(new NewDeviceNotice(id, Instant.now()));

        verify(sender, never()).send(any());
    }

    @Test
    @DisplayName("SEC-007: sendPasswordReset não envia nada para cliente inexistente ou não confirmado")
    void shouldSendNothingForAMissingOrUnconfirmedCustomer() {
        when(customers.findConfirmedEmailById(id)).thenReturn(Optional.empty());

        new CustomerEmailService(customers, tokens, resetTokens, sender, "http://localhost:5173").sendPasswordReset(id);

        verify(sender, never()).send(any());
        verify(resetTokens, never()).reissue(any());
    }
}
