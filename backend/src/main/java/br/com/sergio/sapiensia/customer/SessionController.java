package br.com.sergio.sapiensia.customer;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer login and "who am I". {@code DELETE /api/sessions/current} (logout) is configured in SecurityConfig;
 * {@code DELETE /api/sessions} (log out of all devices) is handled here.
 */
@RestController
@RequestMapping("/api/sessions")
class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionStrategy = new ChangeSessionIdAuthenticationStrategy();
    private final CsrfTokenRepository csrfTokenRepository;
    private final HttpSessionSecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private final LoginAttemptLimiter limiter;
    private final ClientIpResolver clientIp;

    private static final String DEVICE_COOKIE = "SAPIENSIA_DEVICE";
    private static final Duration DEVICE_COOKIE_MAX_AGE = Duration.ofDays(365);

    private final CustomerRepository customers;
    private final CustomerService customerService;
    private final boolean secureCookie;

    SessionController(
            CustomerRepository customers,
            CustomerUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder,
            CsrfTokenRepository csrfTokenRepository,
            LoginAttemptLimiter limiter,
            ClientIpResolver clientIp,
            CustomerService customerService,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie) {
        this.customers = customers;
        this.customerService = customerService;
        this.secureCookie = secureCookie;
        this.limiter = limiter;
        this.clientIp = clientIp;
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        this.authenticationManager = new ProviderManager(provider);
        this.csrfTokenRepository = csrfTokenRepository;
    }

    /** 204 and the session cookie, or an empty 401 that is identical for every kind of failure. */
    @PostMapping
    ResponseEntity<Void> login(
            @RequestBody LoginRequest login, HttpServletRequest request, HttpServletResponse response) {
        // such inputs can never match an account: refuse them without touching the limiter and without hashing
        if (login.email() == null
                || login.email().isBlank()
                || login.password() == null
                || login.email().length() > MAX_EMAIL_LENGTH
                || login.password().length() > MAX_PASSWORD_LENGTH) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // before any authentication: a locked account refuses even the correct password, and no hash is computed.
        // An admitted attempt is already counted as a failure (see LoginAttemptLimiter), so parallel requests cannot
        // all get past the check before the first failure is recorded.
        LoginAttemptLimiter.Admission admission = limiter.admit(login.email(), clientIp.resolve(request));
        if (!admission.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(
                            HttpHeaders.RETRY_AFTER,
                            String.valueOf(admission.blockedFor().toSeconds()))
                    .build();
        }
        Authentication authentication;
        CustomerUserDetailsService.CustomerPrincipal fullPrincipal;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(login.email(), login.password()));
            fullPrincipal = (CustomerUserDetailsService.CustomerPrincipal) authentication.getPrincipal();
        } catch (InternalAuthenticationServiceException e) {
            // Spring wraps a failure of the user lookup (database down) in this AuthenticationException: it is not
            // a wrong password, so it must not count against the customer, and the answer must not say it was one.
            // Answered here with 503 because a rethrown AuthenticationException would be turned into a 401 by Spring
            // Security. Only the class of the cause is logged: its message could carry the e-mail.
            limiter.release(login.email(), admission);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.error(
                    "Login unavailable: the customer lookup failed ({})",
                    cause.getClass().getName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (AuthenticationException e) {
            // a wrong password keeps the failure that was reserved for it
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (RuntimeException e) {
            // an unexpected error is not a failure of the customer: give the reserved place back
            limiter.release(login.email(), admission);
            throw e;
        }
        // checked after the password, so a wrong password and an unconfirmed account cost and answer the same
        if (!fullPrincipal.emailConfirmed()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        limiter.recordSuccess(login.email());
        rememberDevice(fullPrincipal.credentials().id(), request, response);
        // the session keeps the principal without the password hash
        CustomerUserDetailsService.CustomerPrincipal principal = fullPrincipal.withoutPassword();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        // New session id and new CSRF token at login (session fixation). The token is issued here because
        // CsrfAuthenticationStrategy only expires the old cookie, so the SPA would have no valid cookie for logout.
        sessionStrategy.onAuthentication(authentication, request, response);
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
        securityContextRepository.saveContext(context, request, response);
        return ResponseEntity.noContent().build();
    }

    /**
     * Registers the browser of a correct login and refreshes its device cookie. The notice never changes the answer of
     * the login: on any failure only the exception type is logged (the message could carry the e-mail or the token).
     */
    private void rememberDevice(UUID customerId, HttpServletRequest request, HttpServletResponse response) {
        try {
            String cookieToken = null;
            if (request.getCookies() != null) {
                for (Cookie cookie : request.getCookies()) {
                    if (DEVICE_COOKIE.equals(cookie.getName())) {
                        cookieToken = cookie.getValue();
                    }
                }
            }
            String token = customerService.recognizeDevice(customerId, cookieToken, Instant.now());
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    ResponseCookie.from(DEVICE_COOKIE, token)
                            .maxAge(DEVICE_COOKIE_MAX_AGE)
                            .path("/api/sessions")
                            .httpOnly(true)
                            .secure(secureCookie)
                            .sameSite("Lax")
                            .build()
                            .toString());
        } catch (RuntimeException e) {
            log.warn("Could not register the login device ({})", e.getClass().getName());
        }
    }

    /** Ends every session of the account, this one included (SEC-009, SEC-006): 204 and the cookie is deleted. */
    @DeleteMapping
    ResponseEntity<Void> logoutEverywhere(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        var principal = (CustomerUserDetailsService.CustomerPrincipal) authentication.getPrincipal();
        customers.incrementSessionVersion(principal.credentials().id());
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        Cookie cookie = new Cookie("JSESSIONID", "");
        cookie.setMaxAge(0);
        cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
        response.addCookie(cookie);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/current")
    Map<String, String> current(Authentication authentication) {
        return Map.of("email", authentication.getName());
    }
}
