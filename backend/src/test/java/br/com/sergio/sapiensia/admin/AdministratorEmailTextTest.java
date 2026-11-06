package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdministratorEmailTextTest {

    private static final String LINK = "https://sapiensia.test/painel/definir-senha#token";

    @Test
    @DisplayName("SEC-007: com o padrão (PT24H) o e-mail diz exatamente o texto aprovado")
    void shouldKeepTheApprovedSentenceForTheDefaultTtl() {
        assertThat(AdministratorEmailService.text(LINK, Duration.ofHours(24))).isEqualTo("""
                        Olá!

                        Uma conta de Administrador da SapiensIA foi criada para este e-mail. Use o link abaixo para \
                        definir a sua senha. O link vale por 24 horas e só pode ser usado uma vez.

                        https://sapiensia.test/painel/definir-senha#token

                        Se você não esperava esta mensagem, não use o link.
                        """);
    }

    @Test
    @DisplayName("SEC-007: a validade do texto acompanha o app.admin.password-link-ttl")
    void shouldStateTheConfiguredTtl() {
        assertThat(AdministratorEmailService.text(LINK, Duration.ofHours(1)))
                .contains("O link vale por 1 hora e só pode ser usado uma vez.");
        assertThat(AdministratorEmailService.text(LINK, Duration.ofHours(48)))
                .contains("O link vale por 48 horas e só pode ser usado uma vez.");
        assertThat(AdministratorEmailService.text(LINK, Duration.ofMinutes(90)))
                .contains("O link vale por 90 minutos e só pode ser usado uma vez.");
        assertThat(AdministratorEmailService.text(LINK, Duration.ofMinutes(1)))
                .contains("O link vale por 1 minuto e só pode ser usado uma vez.");
    }

    @Test
    @DisplayName("SEC-007: a validade do texto arredonda para cima em minutos inteiros")
    void shouldRoundTheValidityUpToWholeMinutes() {
        assertThat(AdministratorEmailService.text(LINK, Duration.ofMinutes(90)))
                .contains("O link vale por 90 minutos e só pode ser usado uma vez.");
        assertThat(AdministratorEmailService.text(LINK, Duration.ofSeconds(90)))
                .contains("O link vale por 2 minutos e só pode ser usado uma vez.");
        assertThat(AdministratorEmailService.text(LINK, Duration.ofMinutes(60).plusSeconds(1)))
                .contains("O link vale por 61 minutos e só pode ser usado uma vez.");
    }

    @Test
    @DisplayName("SEC-007: um app.admin.password-link-ttl menor que 1 minuto impede a subida")
    void shouldRejectALinkTtlShorterThanOneMinute() {
        assertThatThrownBy(() -> new AdministratorPasswordTokens(null, Duration.ofSeconds(59)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.admin.password-link-ttl");
        assertThat(new AdministratorPasswordTokens(null, Duration.ofMinutes(1)).ttl())
                .isEqualTo(Duration.ofMinutes(1));
    }
}
