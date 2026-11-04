package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
    @DisplayName("SEC-014: shouldIgnoreForgedHeaderWhenNoHeaderIsConfigured")
    void shouldIgnoreForgedHeaderWhenNoHeaderIsConfigured() {
        MockHttpServletRequest request = request();
        request.addHeader("Fly-Client-IP", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "203.0.113.8");

        assertThat(resolver("").resolve(request)).isEqualTo("10.0.0.1");
        assertThat(resolver(null).resolve(request)).isEqualTo("10.0.0.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.7", "192.0.2.1"})
    @DisplayName("SEC-014: shouldAcceptLiteralIpv4FromConfiguredHeader")
    void shouldAcceptLiteralIpv4FromConfiguredHeader(String ip) {
        assertThat(resolveHeader(ip)).isEqualTo(ip);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2001:db8::1", "::1", "::ffff:192.0.2.1", "2001:DB8:0:0:0:0:0:2"})
    @DisplayName("SEC-014: shouldAcceptLiteralIpv6FromConfiguredHeader")
    void shouldAcceptLiteralIpv6FromConfiguredHeader(String ip) {
        assertThat(resolveHeader(ip)).isNotEqualTo("10.0.0.1");
    }

    @ParameterizedTest
    @CsvSource({
        "2001:DB8::1, 2001:db8::1",
        "2001:db8::1, 2001:db8:0:0:0:0:0:1",
        "::1, 0:0:0:0:0:0:0:1",
        "::ffff:192.0.2.1, 192.0.2.1"
    })
    @DisplayName("SEC-014: shouldMapEquivalentFormsOfTheSameIpToTheSameKey")
    void shouldMapEquivalentFormsOfTheSameIpToTheSameKey(String first, String second) {
        assertThat(resolveHeader(first)).isEqualTo(resolveHeader(second));
    }

    private static String resolveHeader(String value) {
        MockHttpServletRequest request = request();
        request.addHeader("Fly-Client-IP", value);
        return resolver("Fly-Client-IP").resolve(request);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "deadbeef.example.com",
                "999.1.1.1",
                "1.2.3",
                "1.2.3.4.5",
                "203.0.113. 7",
                "2001:db8::1 ::2",
                "fe80::1%eth0",
                "203.0.113.7:5678",
                "[2001:db8::1]:443",
                "::g",
                "1:2:3:4:5:6:7:8:9",
                // A leading '.' makes InetAddress.getByName do a real DNS lookup instead of a literal parse;
                // the guard in the resolver is what prevents it (DNS itself is not observable here).
                ".:1",
                "..::"
            })
    @DisplayName("SEC-014: shouldFallBackToRemoteAddrWhenHeaderIsNotALiteralIp")
    void shouldFallBackToRemoteAddrWhenHeaderIsNotALiteralIp(String value) {
        MockHttpServletRequest request = request();
        request.addHeader("Fly-Client-IP", value);

        assertThat(resolver("Fly-Client-IP").resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("SEC-014: shouldFallBackToRemoteAddrWhenHeaderIsTooLong")
    void shouldFallBackToRemoteAddrWhenHeaderIsTooLong() {
        MockHttpServletRequest request = request();
        request.addHeader("Fly-Client-IP", "1".repeat(100));

        assertThat(resolver("Fly-Client-IP").resolve(request)).isEqualTo("10.0.0.1");
    }
}
