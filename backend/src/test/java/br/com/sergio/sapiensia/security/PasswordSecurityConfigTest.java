package br.com.sergio.sapiensia.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class PasswordSecurityConfigTest {

    private static final String PASSWORD = "password";
    private static final String SHA1 = "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8";
    private static final String SUFFIX = SHA1.substring(5);
    private static final String RANGE_URL = "https://api.pwnedpasswords.com/range/5BAA6";

    private final PasswordSecurityConfig config = new PasswordSecurityConfig();

    @Test
    @DisplayName("SEC-003: a senha é guardada em Argon2id com os parâmetros mínimos da OWASP")
    void shouldStorePasswordAsArgon2idWithOwaspMinimumParameters() {
        PasswordEncoder encoder = config.passwordEncoder();

        String hash = encoder.encode("correct horse battery");

        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain("correct horse battery");
        assertThat(encoder.matches("correct horse battery", hash)).isTrue();
        assertThat(encoder.matches("wrong horse battery", hash)).isFalse();
    }

    @Test
    @DisplayName("SEC-002: só os 5 primeiros caracteres do SHA-1 saem para o HIBP")
    void shouldSendOnlyTheFirstFiveCharactersOfTheSha1() {
        Fixture fixture = fixture();
        fixture.server
                .expect(requestTo(RANGE_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Add-Padding", "true"))
                .andRespond(withSuccess("0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n" + SUFFIX + ":3861493", null));

        assertThat(fixture.checker.check(PASSWORD).isCompromised()).isTrue();
        fixture.server.verify();
    }

    @Test
    @DisplayName("SEC-002: entradas de preenchimento (contagem 0) são ignoradas")
    void shouldIgnorePaddingEntries() {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo(RANGE_URL)).andRespond(withSuccess(SUFFIX + ":0", null));

        assertThat(fixture.checker.check(PASSWORD).isCompromised()).isFalse();
    }

    @Test
    @DisplayName("SEC-002: senha que não está na lista não é vazada")
    void shouldNotFlagPasswordMissingFromTheList() {
        Fixture fixture = fixture();
        fixture.server
                .expect(requestTo(RANGE_URL))
                .andRespond(withSuccess("0018A45C4D1DEF81644B54AB7F969B88D65:1", null));

        assertThat(fixture.checker.check(PASSWORD).isCompromised()).isFalse();
    }

    @Test
    @DisplayName("SEC-002: HIBP com erro 500 não derruba o cadastro")
    void shouldNotRejectWhenHibpReturnsServerError() {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo(RANGE_URL)).andRespond(withServerError());

        assertThat(fixture.checker.check(PASSWORD).isCompromised()).isFalse();
    }

    @Test
    @DisplayName("SEC-002: a falha do HIBP não escreve a senha nem o hash no log")
    void shouldNotLogPasswordOrHashWhenHibpFails(CapturedOutput output) {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo(RANGE_URL)).andRespond(withServerError());

        fixture.checker.check(PASSWORD);

        assertThat(output.getAll())
                .contains("Request for leaked passwords failed")
                .doesNotContain(SHA1)
                .doesNotContain(SHA1.toLowerCase());
    }

    private Fixture fixture() {
        RestClient.Builder builder = spy(RestClient.builder());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // keep the mock request factory: the config would otherwise replace it with the real one
        doReturn(builder).when(builder).requestFactory(any());
        return new Fixture(server, config.compromisedPasswordChecker(builder));
    }

    private record Fixture(MockRestServiceServer server, CompromisedPasswordChecker checker) {}
}
