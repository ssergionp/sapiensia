package br.com.sergio.sapiensia.web;

import br.com.sergio.sapiensia.customer.ClientIpResolver;
import br.com.sergio.sapiensia.customer.CurrentCustomer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Charges one request to the limit of an operation before the controller runs (so before any 404 or call to the
 * provider). The key per account is the logged-in Customer's id when the operation has one; the IP comes only from
 * {@link ClientIpResolver}.
 */
final class RateLimitInterceptor implements HandlerInterceptor {

    private final RequestRateLimiter limiter;
    private final ClientIpResolver clientIp;
    private final RateLimitedOperation operation;
    private final String method;
    private final boolean perCustomer;

    RateLimitInterceptor(
            RequestRateLimiter limiter,
            ClientIpResolver clientIp,
            RateLimitedOperation operation,
            String method,
            boolean perCustomer) {
        this.limiter = limiter;
        this.clientIp = clientIp;
        this.operation = operation;
        this.method = method;
        this.perCustomer = perCustomer;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!method.equals(request.getMethod())) {
            return true;
        }
        // another principal (or none) is limited by IP alone instead of failing the request
        Optional<String> account = perCustomer
                ? CurrentCustomer.idIfCustomer(
                                SecurityContextHolder.getContext().getAuthentication())
                        .map(Object::toString)
                : Optional.empty();
        limiter.require(operation, clientIp.resolve(request), account);
        return true;
    }
}
