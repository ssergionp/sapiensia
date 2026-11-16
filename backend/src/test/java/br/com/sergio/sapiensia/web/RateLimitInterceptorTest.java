package br.com.sergio.sapiensia.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.sergio.sapiensia.customer.ClientIpResolver;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitInterceptorTest {

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static RequestRateLimiter limiter() {
        RateLimitProperties.Limit both = new RateLimitProperties.Limit(2, TEN_MINUTES, 5, TEN_MINUTES);
        RateLimitProperties properties =
                new RateLimitProperties(0, both, both, both, both, both, both, both, both, both);
        return new RequestRateLimiter(properties, Clock.systemUTC());
    }

    @Test
    @DisplayName("SEC-014: principal que não é Cliente usa só o IP, sem lançar")
    void shouldFallBackToTheIpAloneWhenThePrincipalIsNotACustomer() {
        RequestRateLimiter limiter = limiter();
        ClientIpResolver clientIp = mock(ClientIpResolver.class);
        when(clientIp.resolve(org.mockito.ArgumentMatchers.any())).thenReturn("192.0.2.1");
        RateLimitInterceptor interceptor =
                new RateLimitInterceptor(limiter, clientIp, RateLimitedOperation.ORDER_CREATE, "POST", true);

        for (Object principal : List.of("some-admin", "anonymousUser")) {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            principal.equals("anonymousUser")
                                    ? new AnonymousAuthenticationToken(
                                            "key", principal, AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
                                    : UsernamePasswordAuthenticationToken.authenticated(
                                            principal, "x", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
            assertThatCode(() -> interceptor.preHandle(
                            new MockHttpServletRequest("POST", "/api/orders"),
                            new MockHttpServletResponse(),
                            new Object()))
                    .doesNotThrowAnyException();
        }
        SecurityContextHolder.clearContext();
        assertThatCode(() -> interceptor.preHandle(
                        new MockHttpServletRequest("POST", "/api/orders"), new MockHttpServletResponse(), new Object()))
                .doesNotThrowAnyException();

        // only the IP key exists, and it was charged three times
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }
}
