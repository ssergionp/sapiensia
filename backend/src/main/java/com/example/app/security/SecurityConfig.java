package com.example.app.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

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

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/api/system/health", "/api/system/health/**")
                        .permitAll()
                        // a new endpoint starts denied: open it here, with the role or capability it requires
                        .requestMatchers("/api/**")
                        .denyAll()
                        .anyRequest()
                        .permitAll())
                .csrf(csrf -> csrf.spa())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .frameOptions(frame -> frame.deny()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
