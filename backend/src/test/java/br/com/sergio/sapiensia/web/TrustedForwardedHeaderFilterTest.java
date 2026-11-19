package br.com.sergio.sapiensia.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TrustedForwardedHeaderFilterTest {

    private final TrustedForwardedHeaderFilter filter = new TrustedForwardedHeaderFilter();

    private MockHttpServletRequest baseRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.setServerName("sapiensia.example");
        request.setServerPort(80);
        return request;
    }

    private HttpServletRequest run(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    @Test
    @DisplayName("a forged X-Forwarded-Host does not change the host the application sees")
    void shouldIgnoreAForgedForwardedHost() throws Exception {
        MockHttpServletRequest request = baseRequest();
        request.addHeader("X-Forwarded-Host", "evil.example");

        HttpServletRequest seen = run(request);

        assertThat(seen.getServerName()).isEqualTo("sapiensia.example");
        assertThat(seen.getRequestURL().toString()).doesNotContain("evil.example");
    }

    @Test
    @DisplayName("a forged X-Forwarded-Prefix does not change the context path or the URI")
    void shouldIgnoreAForgedForwardedPrefix() throws Exception {
        MockHttpServletRequest request = baseRequest();
        request.addHeader("X-Forwarded-Prefix", "/evil");

        HttpServletRequest seen = run(request);

        assertThat(seen.getContextPath()).isEmpty();
        assertThat(seen.getRequestURI()).isEqualTo("/api/products");
    }

    @Test
    @DisplayName("the standard Forwarded header is ignored while X-Forwarded-Proto still applies")
    void shouldIgnoreTheStandardForwardedHeader() throws Exception {
        MockHttpServletRequest request = baseRequest();
        request.addHeader("Forwarded", "host=evil.example;proto=http");
        request.addHeader("X-Forwarded-Proto", "https");

        HttpServletRequest seen = run(request);

        assertThat(seen.getServerName()).isEqualTo("sapiensia.example");
        assertThat(seen.isSecure()).isTrue();
        assertThat(seen.getScheme()).isEqualTo("https");
    }

    @Test
    @DisplayName("the proto and port set by the proxy are still trusted")
    void shouldStillTrustTheProtoAndPortTheProxySets() throws Exception {
        MockHttpServletRequest request = baseRequest();
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Port", "443");

        HttpServletRequest seen = run(request);

        assertThat(seen.isSecure()).isTrue();
        assertThat(seen.getScheme()).isEqualTo("https");
        assertThat(seen.getServerPort()).isEqualTo(443);
    }

    @Test
    @DisplayName("the hidden headers are ignored whatever the case of their names")
    void shouldHideTheHeadersWhateverTheCase() throws Exception {
        MockHttpServletRequest request = baseRequest();
        request.addHeader("x-forwarded-host", "evil.example");

        HttpServletRequest seen = run(request);

        assertThat(seen.getServerName()).isEqualTo("sapiensia.example");
    }

    @Test
    @DisplayName("the hidden headers are not visible to anything downstream")
    void shouldNotShowTheHiddenHeadersDownstream() throws Exception {
        MockHttpServletRequest request = baseRequest();
        request.addHeader("Forwarded", "host=evil.example");
        request.addHeader("X-Forwarded-Host", "evil.example");
        request.addHeader("X-Forwarded-Prefix", "/evil");

        HttpServletRequest seen = run(request);

        assertThat(seen.getHeader("Forwarded")).isNull();
        assertThat(seen.getHeader("X-Forwarded-Host")).isNull();
        assertThat(seen.getHeader("X-Forwarded-Prefix")).isNull();
        assertThat(Collections.list(seen.getHeaders("X-Forwarded-Host"))).isEmpty();
        assertThat(Collections.list(seen.getHeaderNames()))
                .noneMatch(name -> name.equalsIgnoreCase("Forwarded")
                        || name.equalsIgnoreCase("X-Forwarded-Host")
                        || name.equalsIgnoreCase("X-Forwarded-Prefix"));
    }

    @Test
    @DisplayName("a request without forwarded headers passes through untouched")
    void shouldPassARequestWithoutForwardedHeadersUntouched() throws Exception {
        HttpServletRequest seen = run(baseRequest());

        assertThat(seen).isNotNull();
        assertThat(seen.getServerName()).isEqualTo("sapiensia.example");
        assertThat(seen.getScheme()).isEqualTo("http");
    }
}
