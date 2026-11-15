package br.com.sergio.sapiensia.email;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ResendEmailSenderTest {

    private static final String API_KEY = "re_test_key";
    private static final String FROM = "SapiensIA <nao-responda@sapiensia.app.br>";
    private static final EmailMessage MESSAGE = new EmailMessage("ana@example.com", "Confirme seu e-mail", "Olá, Ana");

    @Test
    void shouldPostJsonWithBearerKeyToResend() {
        Fixture fixture = fixture();
        fixture.server
                .expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.from").value(FROM))
                .andExpect(jsonPath("$.to.length()").value(1))
                .andExpect(jsonPath("$.to[0]").value("ana@example.com"))
                .andExpect(jsonPath("$.subject").value("Confirme seu e-mail"))
                .andExpect(jsonPath("$.text").value("Olá, Ana"))
                .andRespond(withSuccess("{\"id\":\"abc\"}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> fixture.sender.send(MESSAGE)).doesNotThrowAnyException();
        fixture.server.verify();
    }

    @Test
    void shouldSendIdempotencyKeyHeaderWhenMessageHasKey() {
        Fixture fixture = fixture();
        fixture.server
                .expect(requestTo("https://api.resend.com/emails"))
                .andExpect(header("Idempotency-Key", "order-paid-email/abc"))
                .andRespond(withSuccess("{\"id\":\"abc\"}", MediaType.APPLICATION_JSON));

        fixture.sender.send(new EmailMessage("ana@example.com", "s", "t", "order-paid-email/abc"));
        fixture.server.verify();
    }

    @Test
    void shouldOmitIdempotencyKeyHeaderWhenMessageHasNoKey() {
        Fixture fixture = fixture();
        fixture.server
                .expect(requestTo("https://api.resend.com/emails"))
                .andExpect(request -> org.assertj.core.api.Assertions.assertThat(
                                request.getHeaders().containsHeader("Idempotency-Key"))
                        .isFalse())
                .andRespond(withSuccess("{\"id\":\"abc\"}", MediaType.APPLICATION_JSON));

        fixture.sender.send(MESSAGE);
        fixture.server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 422, 429})
    @DisplayName("SEC-007: falha do Resend vira EmailDeliveryException sem e-mail nem corpo na mensagem")
    void shouldFailWithStatusOnlyWhenResendRejects(int status) {
        Fixture fixture = fixture();
        fixture.server
                .expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withStatus(HttpStatus.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"invalid to ana@example.com\"}"));

        assertThatThrownBy(() -> fixture.sender.send(MESSAGE))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining(String.valueOf(status))
                .message()
                .doesNotContain("ana@example.com")
                .doesNotContain("invalid to")
                .doesNotContain(API_KEY);
    }

    @Test
    void shouldWrapConnectionFailureWithoutRecipient() {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://api.resend.com/emails")).andRespond(request -> {
            throw new IOException("connection reset while sending to ana@example.com");
        });

        assertThatThrownBy(() -> fixture.sender.send(MESSAGE))
                .isInstanceOf(EmailDeliveryException.class)
                .message()
                .doesNotContain("ana@example.com")
                .doesNotContain(API_KEY);
    }

    @Test
    void shouldRefuseToStartWithoutApiKey() {
        assertThatThrownBy(() -> new ResendEmailSender(RestClient.builder(), " ", FROM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_API_KEY");
    }

    private Fixture fixture() {
        RestClient.Builder builder = spy(RestClient.builder());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // keep the mock request factory: the sender would otherwise replace it with the real one
        doReturn(builder).when(builder).requestFactory(any());
        return new Fixture(server, new ResendEmailSender(builder, API_KEY, FROM));
    }

    private record Fixture(MockRestServiceServer server, ResendEmailSender sender) {}
}
