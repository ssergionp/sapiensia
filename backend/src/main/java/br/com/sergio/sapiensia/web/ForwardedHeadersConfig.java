package br.com.sergio.sapiensia.web;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Replaces the {@link ForwardedHeaderFilter} that Spring Boot registers for {@code server.forward-headers-strategy=
 * framework} with {@link TrustedForwardedHeaderFilter}. The declared type is {@code FilterRegistrationBean<
 * ForwardedHeaderFilter>} because that is what makes Spring Boot's own {@code @ConditionalOnMissingFilterBean} back
 * off; order and dispatcher types are the same as Spring Boot's.
 */
@Configuration(proxyBeanMethods = false)
class ForwardedHeadersConfig {

    @Bean
    @ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
    FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
        FilterRegistrationBean<ForwardedHeaderFilter> registration =
                new FilterRegistrationBean<>(new TrustedForwardedHeaderFilter());
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
