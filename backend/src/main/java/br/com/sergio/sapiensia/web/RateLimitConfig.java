package br.com.sergio.sapiensia.web;

import br.com.sergio.sapiensia.customer.ClientIpResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Where each limit applies (SEC-014). Sign-up and password reset request are limited by e-mail too, so their
 * controller calls the limiter after reading the body. Login keeps its own limiter (ADR-0004), and the order status
 * poll is not limited.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig implements WebMvcConfigurer {

    private final RequestRateLimiter limiter;
    private final ClientIpResolver clientIp;

    RateLimitConfig(RequestRateLimiter limiter, ClientIpResolver clientIp) {
        this.limiter = limiter;
        this.clientIp = clientIp;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        add(registry, RateLimitedOperation.ORDER_CREATE, "POST", true, "/api/orders");
        add(registry, RateLimitedOperation.ORDER_READ, "GET", true, "/api/orders/{id}");
        add(registry, RateLimitedOperation.REFUND_REQUEST, "POST", true, "/api/orders/{id}/refund-request");
        add(registry, RateLimitedOperation.DOWNLOAD, "POST", true, "/api/library/{productId}/download");
        add(
                registry,
                RateLimitedOperation.COVER,
                "GET",
                false,
                "/api/products/{id}/cover",
                "/api/library/{productId}/cover");
        add(
                registry,
                RateLimitedOperation.PASSWORD_RESET_CONFIRM,
                "POST",
                false,
                "/api/customers/password-resets/confirmation");
        add(registry, RateLimitedOperation.EMAIL_CONFIRMATION, "POST", false, "/api/customers/email-confirmations");
        add(registry, RateLimitedOperation.ADMIN_PASSWORD_SETUP, "POST", false, "/api/admin/password-setup");
    }

    private void add(
            InterceptorRegistry registry,
            RateLimitedOperation operation,
            String method,
            boolean perCustomer,
            String... patterns) {
        registry.addInterceptor(new RateLimitInterceptor(limiter, clientIp, operation, method, perCustomer))
                .addPathPatterns(patterns);
    }
}
