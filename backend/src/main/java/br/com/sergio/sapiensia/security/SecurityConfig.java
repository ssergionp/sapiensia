package br.com.sergio.sapiensia.security;

import br.com.sergio.sapiensia.admin.AdminMfaAccess;
import br.com.sergio.sapiensia.admin.AdministratorPrincipal;
import br.com.sergio.sapiensia.admin.Capability;
import br.com.sergio.sapiensia.admin.PendingAdministrator;
import br.com.sergio.sapiensia.customer.SessionVersionFilter;
import br.com.sergio.sapiensia.payment.AsaasWebhookAccess;
import br.com.sergio.sapiensia.product.ProductFileReplacementAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.authorization.AllRequiredFactorsAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

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

    /**
     * The public Catalog (#20, ADR-0007) and the cover of a published Product (#21): GET only, so
     * /products/{id}/file and every other verb stay denied. The same matcher opens the routes
     * and keeps the XSRF-TOKEN cookie off them.
     */
    static final OrRequestMatcher PUBLIC_CATALOG = new OrRequestMatcher(
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/products"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/products/*"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/products/*/cover"));

    /** One repository for the filter chain and for the login, which renews the token by hand. */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return new NoCookieOnPublicCatalog(CookieCsrfTokenRepository.withHttpOnlyFalse());
    }

    /**
     * The CSRF filter generates and saves a token on any request that has none, even a GET that needs no token, so
     * the public Catalog (#20) would hand every Visitor an XSRF-TOKEN cookie. Saving is skipped for those three GET
     * routes only (A4); everything else, including the login that renews the token by hand, behaves as before.
     */
    static final class NoCookieOnPublicCatalog implements CsrfTokenRepository {

        private final CsrfTokenRepository delegate;

        NoCookieOnPublicCatalog(CsrfTokenRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public CsrfToken generateToken(HttpServletRequest request) {
            return delegate.generateToken(request);
        }

        @Override
        public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
            if (!PUBLIC_CATALOG.matches(request)) {
                delegate.saveToken(token, request, response);
            }
        }

        @Override
        public CsrfToken loadToken(HttpServletRequest request) {
            return delegate.loadToken(request);
        }
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

    /**
     * A panel route: the capability AND the password and TOTP factors. The factors are a second lock, independent of
     * the capabilities (which only the full Administrator has). Not @EnableMultiFactorAuthentication: that would
     * require the factors on the customer routes too.
     */
    public static AuthorizationManager<RequestAuthorizationContext> panel(Capability capability) {
        return AuthorizationManagers.allOf(
                AuthorityAuthorizationManager.hasAuthority(capability.authority()),
                AllRequiredFactorsAuthorizationManager.<RequestAuthorizationContext>builder()
                        .requireFactor(factor -> factor.passwordAuthority())
                        .requireFactor(factor -> factor.authority(AdministratorPrincipal.TOTP_AUTHORITY))
                        .build());
    }

    private static final JacksonJsonHttpMessageConverter PROBLEM_WRITER = new JacksonJsonHttpMessageConverter();

    /**
     * A pending Administrator (password done, code missing) denied anywhere gets 401, like an anonymous visitor: the
     * SPA then shows the login, not "this area is only for the Administrator". Everyone else keeps the usual 403, and
     * so does a CSRF failure. Two denials of the product file route carry their own answer: a published Product needs a
     * recent authentication (403 with the problem body, so the screen can ask for it) and a database that cannot tell
     * (503, no body).
     */
    static AccessDeniedHandler accessDeniedHandler() {
        AccessDeniedHandler forbidden = new AccessDeniedHandlerImpl();
        return (request, response, exception) -> {
            if (exception instanceof AuthorizationDeniedException denied) {
                if (denied.getAuthorizationResult() instanceof ProductFileReplacementAccess.ReauthenticationRequired) {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    PROBLEM_WRITER.write(
                            ProductFileReplacementAccess.problem(request),
                            MediaType.APPLICATION_PROBLEM_JSON,
                            new ServletServerHttpResponse(response));
                    return;
                }
                if (denied.getAuthorizationResult() instanceof ProductFileReplacementAccess.Unavailable) {
                    response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                    return;
                }
            }
            Authentication authentication = SecurityContextHolder.getContextHolderStrategy()
                    .getContext()
                    .getAuthentication();
            if (!(exception instanceof CsrfException)
                    && authentication != null
                    && authentication.getPrincipal() instanceof PendingAdministrator) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                return;
            }
            forbidden.handle(request, response, exception);
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            SessionVersionFilter sessionVersionFilter,
            AdminMfaAccess adminMfaAccess,
            ProductFileReplacementAccess productFileReplacementAccess,
            AsaasWebhookAccess asaasWebhookAccess)
            throws Exception {
        // right after the context is loaded from the session: ends sessions opened before a password change (SEC-006)
        http.addFilterAfter(sessionVersionFilter, SecurityContextHolderFilter.class);
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/api/system/health", "/api/system/health/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/sessions")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/sessions/current")
                        .hasRole("CUSTOMER")
                        // log out of all devices (SEC-009)
                        .requestMatchers(HttpMethod.DELETE, "/api/sessions")
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
                        // the link token in the body is the credential; CSRF stays on
                        .requestMatchers(HttpMethod.POST, "/api/admin/password-setup")
                        .permitAll()
                        // the panel: the password step is public (CSRF stays on) and leaves the session "MFA
                        // pending"; the code step accepts only that pending state, for app.admin.mfa-pending-ttl
                        .requestMatchers(HttpMethod.POST, "/api/admin/sessions")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/sessions/mfa")
                        .access(adminMfaAccess)
                        .requestMatchers(HttpMethod.GET, "/api/admin/sessions/mfa")
                        .access(adminMfaAccess)
                        .requestMatchers(HttpMethod.POST, "/api/admin/sessions/mfa/enrollment")
                        .access(adminMfaAccess)
                        .requestMatchers(HttpMethod.POST, "/api/admin/sessions/mfa/enrollment/confirmation")
                        .access(adminMfaAccess)
                        .requestMatchers(HttpMethod.POST, "/api/admin/sessions/mfa/recovery-code")
                        .access(adminMfaAccess)
                        // every panel route: the capability AND both factors, two independent locks
                        .requestMatchers(HttpMethod.GET, "/api/admin/sessions/current")
                        .access(panel(Capability.PANEL_ACCESS))
                        // password and a fresh TOTP code typed again by the full Administrator (SEC-008)
                        .requestMatchers(HttpMethod.POST, "/api/admin/sessions/reauthentication")
                        .access(panel(Capability.PANEL_ACCESS))
                        // also asks for a fresh TOTP code in the body, checked by the controller (SEC-008)
                        .requestMatchers(HttpMethod.POST, "/api/admin/recovery-codes")
                        .access(panel(Capability.RECOVERY_CODES_MANAGE))
                        // Products (#17): one segment after /products, so /products/{id}/file stays denied. The price
                        // has its own capability (SEC-029): PRODUCTS_EDIT cannot change it.
                        .requestMatchers(HttpMethod.GET, "/api/admin/products", "/api/admin/products/*")
                        .access(panel(Capability.PRODUCTS_EDIT))
                        .requestMatchers(HttpMethod.POST, "/api/admin/products")
                        .access(panel(Capability.PRODUCTS_EDIT))
                        .requestMatchers(HttpMethod.PUT, "/api/admin/products/*")
                        .access(panel(Capability.PRODUCTS_EDIT))
                        .requestMatchers(HttpMethod.PUT, "/api/admin/products/*/price")
                        .access(panel(Capability.PRODUCTS_PRICE))
                        // the PDF of a Product (#18): POST only (Tomcat parses multipart on POST); GET, PUT and DELETE
                        // on /file stay denied. The lock is checked before the body is read: PRODUCTS_FILE and, for a
                        // Product that was ever published, a recent authentication (#22). The name {id} feeds the
                        // manager.
                        .requestMatchers(HttpMethod.POST, "/api/admin/products/{id}/file")
                        .access(productFileReplacementAccess)
                        // the cover (#21): all three on PRODUCTS_EDIT, no reauthentication (it is presentation)
                        .requestMatchers(HttpMethod.GET, "/api/admin/products/*/cover")
                        .access(panel(Capability.PRODUCTS_EDIT))
                        .requestMatchers(HttpMethod.POST, "/api/admin/products/*/cover")
                        .access(panel(Capability.PRODUCTS_EDIT))
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/products/*/cover")
                        .access(panel(Capability.PRODUCTS_EDIT))
                        // publication (#19): PUT publishes, DELETE unpublishes; no other verb is open
                        .requestMatchers(HttpMethod.PUT, "/api/admin/products/*/publication")
                        .access(panel(Capability.PRODUCTS_PUBLISH))
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/products/*/publication")
                        .access(panel(Capability.PRODUCTS_PUBLISH))
                        .requestMatchers(PUBLIC_CATALOG)
                        .permitAll()
                        // Orders (#24): the logged-in Customer only; the Order is filtered by its owner in the SQL
                        // (SEC-011)
                        .requestMatchers(HttpMethod.POST, "/api/orders")
                        .hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.GET, "/api/orders/*")
                        .hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.GET, "/api/orders/*/status")
                        .hasRole("CUSTOMER")
                        // Library (#32): the logged-in Customer only
                        .requestMatchers(HttpMethod.GET, "/api/library")
                        .hasRole("CUSTOMER")
                        .requestMatchers(HttpMethod.GET, "/api/library/*/cover")
                        .hasRole("CUSTOMER")
                        // local download link (#33): only exists with the file provider
                        .requestMatchers(HttpMethod.GET, "/api/local-downloads/*")
                        .hasRole("CUSTOMER")
                        // Asaas webhook (#26, ADR-0003): the token header and the source IP are the credential; no
                        // session, no CSRF
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/asaas")
                        .access(asaasWebhookAccess)
                        .requestMatchers("/api/**")
                        .denyAll()
                        .anyRequest()
                        .permitAll())
                .csrf(csrf -> csrf.spa()
                        .csrfTokenRepository(csrfTokenRepository)
                        .ignoringRequestMatchers(
                                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/webhooks/asaas")))
                // no login page to redirect to: anonymous requests to protected routes get 401
                // nothing to redirect back to after a login, and the saved request would create a session for every
                // anonymous visitor
                .requestCache(cache -> cache.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(accessDeniedHandler()))
                .logout(logout -> logout.logoutRequestMatcher(
                                PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/sessions/current"))
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies("JSESSIONID"))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .frameOptions(frame -> frame.deny())
                        // the reset link carries its token in the URL fragment: never let it reach another site
                        // (SEC-007)
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                new OrRequestMatcher(
                                        PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/redefinir-senha"),
                                        PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/painel/definir-senha")),
                                new ReferrerPolicyHeaderWriter(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
