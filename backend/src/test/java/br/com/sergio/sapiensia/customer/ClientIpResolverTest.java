package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    private static ClientIpResolver resolver(String header) {
        return new ClientIpResolver(new LoginLimitProperties(
                5, Duration.ofMinutes(15), Duration.ofMinutes(15), 20, Duration.ofMinutes(15), header));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        return request;
    }

    @Test
    void shouldUseFirstItemOfConfiguredHeaderWhenPresent() {
        MockHttpServletRequest request = request();
        request.addHeader("Fly-Client-IP", " 203.0.113.7 , 10.1.1.1");

        assertThat(resolver("Fly-Client-IP").resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void shouldFallBackToRemoteAddrWhenConfiguredHeaderIsAbsent() {
        assertThat(resolver("Fly-Client-IP").resolve(request())).isEqualTo("10.0.0.1");
    }

    @Test
    void shouldFallBackToRemoteAddrWhenConfiguredHeaderIsBlank() {
        MockHttpServletRequest request = request();
        request.addHeader("Fly-Client-IP", "  ");

        assertThat(resolver("Fly-Client-IP").resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void shouldIgnoreForgedHeaderWhenNoHeaderIsConfigured() {
        MockHttpServletRequest request = request();
        request.addHeader("Fly-Client-IP", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "203.0.113.8");

        assertThat(resolver("").resolve(request)).isEqualTo("10.0.0.1");
        assertThat(resolver(null).resolve(request)).isEqualTo("10.0.0.1");
    }
}
