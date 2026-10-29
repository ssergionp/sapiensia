package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.sergio.sapiensia.email.EmailMessage;
import br.com.sergio.sapiensia.email.EmailSender;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CustomerEmailServiceTest {

    private final UUID id = UUID.randomUUID();
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final EmailConfirmationTokens tokens = mock(EmailConfirmationTokens.class);
    private final EmailSender sender = mock(EmailSender.class);

    private EmailMessage sentConfirmationFor(String baseUrl) {
        when(customers.findUnconfirmedEmailById(id)).thenReturn(Optional.of("a@example.com"));
        when(tokens.issue(id)).thenReturn("tok");
        new CustomerEmailService(customers, tokens, sender, baseUrl).sendConfirmation(id);
        return captured();
    }

    private EmailMessage sentNoticeFor(String baseUrl) {
        when(customers.findEmailById(id)).thenReturn(Optional.of("a@example.com"));
        new CustomerEmailService(customers, tokens, sender, baseUrl).sendAccountExistsNotice(id);
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

    @Test
    void shouldBuildTheNoticeLinksWithoutDoubleSlashWhenBaseUrlEndsWithSlash() {
        assertThat(sentNoticeFor("http://localhost:5173/").text())
                .contains("http://localhost:5173/entrar")
                .contains("http://localhost:5173/recuperar-senha")
                .doesNotContain("5173//");
    }

    @Test
    void shouldKeepTheLinksUnchangedWhenBaseUrlHasNoTrailingSlash() {
        assertThat(sentConfirmationFor("http://localhost:5173").text())
                .contains("http://localhost:5173/confirmar-email#tok");
    }
}
