package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomerRequestEmailNormalizationTest {

    @Test
    @DisplayName("O cadastro guarda o e-mail sem espaços nas pontas e em minúsculas, e não mexe na senha")
    void shouldNormalizeTheEmailOfTheRegistration() {
        var request = new RegisterCustomerRequest(" \tCliente@EXAMPLE.com ", " Senha-Longa-123 ");

        assertThat(request.email()).isEqualTo("cliente@example.com");
        assertThat(request.password()).isEqualTo(" Senha-Longa-123 ");
    }

    @Test
    @DisplayName("O pedido de recuperação usa o e-mail sem espaços nas pontas e em minúsculas")
    void shouldNormalizeTheEmailOfThePasswordResetRequest() {
        assertThat(new RequestPasswordResetRequest(" \tCliente@EXAMPLE.com ").email())
                .isEqualTo("cliente@example.com");
    }

    @Test
    @DisplayName("Um e-mail ausente continua ausente, para a validação responder EMAIL_INVALID como antes")
    void shouldKeepAMissingEmailMissing() {
        assertThat(new RegisterCustomerRequest(null, "Senha-Longa-123").email()).isNull();
        assertThat(new RequestPasswordResetRequest(null).email()).isNull();
    }
}
