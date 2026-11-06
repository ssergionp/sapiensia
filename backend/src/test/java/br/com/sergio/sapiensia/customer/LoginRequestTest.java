package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoginRequestTest {

    @Test
    @DisplayName("SEC-010: o toString do pedido de login do Cliente não mostra o e-mail nem a senha")
    void shouldKeepTheEmailAndThePasswordOutOfToString() {
        String text = new LoginRequest("cliente-secreto@example.com", "senha-secreta-456").toString();

        assertThat(text).doesNotContain("cliente-secreto@example.com").doesNotContain("senha-secreta-456");
    }
}
