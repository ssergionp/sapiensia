package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminPasswordSetupRequestTest {

    @Test
    @DisplayName("SEC-007: o toString do pedido de definir a senha do painel não mostra o token nem a senha")
    void shouldKeepTheTokenAndThePasswordOutOfToString() {
        String text = new AdminPasswordSetupRequest("token-secreto-123", "senha-secreta-456").toString();

        assertThat(text).doesNotContain("token-secreto-123").doesNotContain("senha-secreta-456");
    }
}
