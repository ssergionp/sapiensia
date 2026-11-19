package br.com.sergio.sapiensia.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Spring's {@link ForwardedHeaderFilter} that only trusts what the platform proxy really sets.
 *
 * <p>The Fly.io proxy sets {@code X-Forwarded-Proto}, {@code X-Forwarded-Port} and {@code X-Forwarded-Ssl}, which are
 * what the application needs to know the request came over HTTPS. {@code Forwarded}, {@code X-Forwarded-Host} and
 * {@code X-Forwarded-Prefix} are not set by the proxy, so any value that arrives comes from the client; they are hidden
 * before the Spring filter reads them, so a forged host or prefix never reaches the request URL or the context path.
 * {@code X-Forwarded-For} is not touched here: the client address has its own rule (SEC-014, {@code ClientIpResolver}).
 */
final class TrustedForwardedHeaderFilter extends ForwardedHeaderFilter {

    static final Set<String> HIDDEN = Set.of("forwarded", "x-forwarded-host", "x-forwarded-prefix");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        super.doFilterInternal(new HiddenHeadersRequest(request), response, chain);
    }

    private static boolean isHidden(String name) {
        return name != null && HIDDEN.contains(name.toLowerCase(Locale.ROOT));
    }

    private static final class HiddenHeadersRequest extends HttpServletRequestWrapper {

        HiddenHeadersRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return isHidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return isHidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> visible = Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> !isHidden(name))
                    .toList();
            return Collections.enumeration(visible);
        }
    }
}
