package br.com.sergio.sapiensia.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** SEC-010 G1 G3: what the PUBLIC_CATALOG matcher opens (and keeps the XSRF cookie off), and the frozen CSP. */
class PublicCatalogMatcherTest {

    private static boolean matches(String method, String path) {
        return SecurityConfig.PUBLIC_CATALOG.matches(new MockHttpServletRequest(method, path));
    }

    @Test
    @DisplayName("SEC-010 G1: PUBLIC_CATALOG matches GET of the list, the detail and the cover, and nothing else")
    void shouldMatchOnlyTheGetRoutesOfTheCatalogAndTheCover() {
        String id = UUID.randomUUID().toString();

        assertThat(matches("GET", "/api/products")).isTrue();
        assertThat(matches("GET", "/api/products/" + id)).isTrue();
        assertThat(matches("GET", "/api/products/" + id + "/cover")).isTrue();

        assertThat(matches("POST", "/api/products/" + id + "/cover")).isFalse();
        assertThat(matches("PUT", "/api/products/" + id + "/cover")).isFalse();
        assertThat(matches("DELETE", "/api/products/" + id + "/cover")).isFalse();
        assertThat(matches("GET", "/api/products/" + id + "/file")).isFalse();
        assertThat(matches("GET", "/api/products/" + id + "/cover/extra")).isFalse();
        assertThat(matches("GET", "/api/admin/products/" + id + "/cover")).isFalse();
    }

    @Test
    @DisplayName("SEC-010 G3: the Content-Security-Policy constant is unchanged")
    void shouldKeepTheContentSecurityPolicy() {
        assertThat(SecurityConfig.CONTENT_SECURITY_POLICY)
                .isEqualTo("default-src 'self'; script-src 'self'; "
                        + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; "
                        + "connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
                        + "frame-ancestors 'none'");
    }
}
