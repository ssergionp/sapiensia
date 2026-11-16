package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.sergio.sapiensia.alert.AdminAlerts;
import br.com.sergio.sapiensia.customer.ClientIpResolver;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

@ExtendWith(OutputCaptureExtension.class)
class AsaasWebhookAccessTest {

    private static final String TOKEN = "t".repeat(32) + "-secret";
    private static final String ALLOWED_IP = "52.67.12.206";

    private final ClientIpResolver resolver = mock(ClientIpResolver.class);

    private AsaasWebhookAccess access(String... allowedIps) {
        return new AsaasWebhookAccess(
                resolver, new AsaasWebhookProperties(TOKEN, List.of(allowedIps), 65536), mock(AdminAlerts.class));
    }

    private boolean granted(AsaasWebhookAccess access, String ip, String token) {
        when(resolver.resolve(any())).thenReturn(ip);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhooks/asaas");
        if (token != null) {
            request.addHeader(AsaasWebhookAccess.TOKEN_HEADER, token);
        }
        return access.authorize(() -> null, new RequestAuthorizationContext(request))
                .isGranted();
    }

    @Test
    @DisplayName("SEC-023, SEC-024: shouldGrantWhenTokenAndIpMatch")
    void shouldGrantWhenTokenAndIpMatch() {
        assertThat(granted(access(ALLOWED_IP), ALLOWED_IP, TOKEN)).isTrue();
    }

    @Test
    @DisplayName("SEC-023: shouldDenyAPrefixOfTheToken")
    void shouldDenyAPrefixOfTheToken() {
        assertThat(granted(access(ALLOWED_IP), ALLOWED_IP, TOKEN.substring(0, TOKEN.length() - 1)))
                .isFalse();
    }

    @Test
    @DisplayName("SEC-023: shouldDenyATokenOfAnotherLength")
    void shouldDenyATokenOfAnotherLength() {
        assertThat(granted(access(ALLOWED_IP), ALLOWED_IP, TOKEN + "x")).isFalse();
        assertThat(granted(access(ALLOWED_IP), ALLOWED_IP, "x".repeat(255))).isFalse();
    }

    @Test
    @DisplayName("SEC-023: shouldDenyWhenTheHeaderIsMissing")
    void shouldDenyWhenTheHeaderIsMissing() {
        assertThat(granted(access(ALLOWED_IP), ALLOWED_IP, null)).isFalse();
    }

    @Test
    @DisplayName("SEC-023: shouldDenyAnIpOutsideTheList")
    void shouldDenyAnIpOutsideTheList() {
        assertThat(granted(access(ALLOWED_IP), "203.0.113.9", TOKEN)).isFalse();
    }

    @Test
    void shouldMatchIpv6InAnyForm() {
        assertThat(granted(access("::1"), "0:0:0:0:0:0:0:1", TOKEN)).isTrue();
    }

    @Test
    void shouldEvaluateIpAndTokenEvenWhenTheFirstFails(CapturedOutput output) {
        assertThat(granted(access(ALLOWED_IP), "203.0.113.9", "wrong")).isFalse();
        assertThat(output.getAll()).contains("reason=IP_NOT_ALLOWED_AND_TOKEN_INVALID");

        assertThat(granted(access(ALLOWED_IP), "203.0.113.9", TOKEN)).isFalse();
        assertThat(Pattern.compile("reason=IP_NOT_ALLOWED(?!_)")
                        .matcher(output.getAll())
                        .results()
                        .count())
                .isEqualTo(1);

        assertThat(granted(access(ALLOWED_IP), ALLOWED_IP, "wrong")).isFalse();
        assertThat(output.getAll())
                .contains("reason=TOKEN_INVALID")
                .doesNotContain("wrong")
                .doesNotContain(TOKEN);
    }
}
