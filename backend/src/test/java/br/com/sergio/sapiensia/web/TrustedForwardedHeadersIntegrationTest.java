package br.com.sergio.sapiensia.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

@Import(TestcontainersConfiguration.class)
// Same context as FlyClientIpLoginLimitTest (same annotations), so it adds no new Spring context to the test cache.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=fly-client-ip-login-limit",
            "server.forward-headers-strategy=framework",
            "app.login-limit.ip-max-attempts=3",
            "app.login-limit.client-ip-header=Fly-Client-IP"
        })
class TrustedForwardedHeadersIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    List<FilterRegistrationBean<?>> registrations;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("only the trusted forwarded-headers filter is registered, first in the chain")
    void shouldRegisterOnlyTheTrustedForwardedHeaderFilter() {
        List<FilterRegistrationBean<?>> forwarded = registrations.stream()
                .filter(r -> r.getFilter() instanceof ForwardedHeaderFilter)
                .toList();

        assertThat(forwarded).hasSize(1);
        assertThat(forwarded.get(0).getFilter()).isInstanceOf(TrustedForwardedHeaderFilter.class);
        assertThat(forwarded.get(0).getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("the proxy's X-Forwarded-Proto still makes the request count as HTTPS")
    void shouldStillSeeHttpsFromTheProxyProtoHeader() throws Exception {
        URI entrar = URI.create("http://127.0.0.1:" + port + "/entrar");

        HttpResponse<String> viaProxy = http.send(
                HttpRequest.newBuilder(entrar)
                        .header("X-Forwarded-Proto", "https")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> direct =
                http.send(HttpRequest.newBuilder(entrar).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(viaProxy.headers().firstValue("Strict-Transport-Security")).isPresent();
        assertThat(direct.headers().firstValue("Strict-Transport-Security")).isEmpty();
    }
}
