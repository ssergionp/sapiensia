package br.com.sergio.sapiensia.product;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Without the disk provider (prod) the local download route must not exist at all. The security rule for it would
 * answer 401 to an anonymous request and 403 to a logged-in one before Spring MVC could say "no such route"; this
 * filter runs before the security chain and answers the empty 404 of an unregistered route, for any method.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnExpression("'${app.storage.provider}' != 'file'")
class LocalDownloadRouteAbsent extends OncePerRequestFilter {

    private static final String ROUTE = "/api/local-downloads";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals(ROUTE) || path.startsWith(ROUTE + "/")) {
            // setStatus, not sendError: sendError would forward to the error page and add a body
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
