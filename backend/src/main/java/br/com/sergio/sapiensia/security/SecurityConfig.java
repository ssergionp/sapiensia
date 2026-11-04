package br.com.sergio.sapiensia.security;

import br.com.sergio.sapiensia.customer.SessionVersionFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Security baseline for every project created from the template.
 *
 * <ul>
 *   <li>Every route under /api is denied until the project opens it explicitly (deny by default).
 *   <li>The health check and the API docs stay open; the docs are switched off in the prod profile.
 *   <li>The single-page app and its static files are public.
 *   <li>CSRF protection for the SPA: the token travels in the XSRF-TOKEN cookie and the X-XSRF-TOKEN header.
 *   <li>Security headers: CSP, no framing, no MIME sniffing (HSTS is added by Spring Security over HTTPS).
 * </ul>
 */
@Configuration
public class SecurityConfig {

    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; "
            + "connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
            + "frame-ancestors 'none'";

    /** One repository for the filter chain and for the login, which renews the token by hand. */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    /**
     * The filter runs only inside the security chain: as a bean, Spring Boot would also register it in the servlet
     * container, before the security context is loaded.
     */
    @Bean
    FilterRegistrationBean<SessionVersionFilter> sessionVersionFilterRegistration(SessionVersionFilter filter) {
        FilterRegistrationBean<SessionVersionFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, CsrfTokenRepository csrfTokenRepository, SessionVersionFilter sessionVersionFilter)
            throws Exception {
        // right after the context is loaded from the session: ends sessions opened before a password change (SEC-006)
        http.addFilterAfter(sessionVersionFilter, SecurityContextHolderFilter.class);
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/api/system/health", "/api/system/health/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/sessions")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/sessions/current")
                        .hasRole("CUSTOMER")
                        // logout is answered by the logout filter before this rule; kept so the route is listed
                        .requestMatchers(HttpMethod.DELETE, "/api/sessions/current")
                        .hasRole("CUSTOMER")
                        // a new endpoint starts denied: open it here, with the role or capability it requires
                        .requestMatchers(HttpMethod.POST, "/api/customers")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/customers/email-confirmations")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/customers/password-resets")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/customers/password-resets/confirmation")
                        .permitAll()
                        .requestMatchers("/api/**")
                        .denyAll()
                        .anyRequest()
                        .permitAll())
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
                // no login page to redirect to: anonymous requests to protected routes get 401
                // nothing to redirect back to after a login, and the saved request would create a session for every
                // anonymous visitor
                .requestCache(cache -> cache.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .logout(logout -> logout.logoutRequestMatcher(
                                PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/sessions/current"))
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies("JSESSIONID"))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .frameOptions(frame -> frame.deny()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
