package br.com.sergio.sapiensia.customer;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer login and "who am I". {@code DELETE /api/sessions/current} (logout) is configured in SecurityConfig;
 * {@code DELETE /api/sessions} (log out of all devices) is handled here. The login procedure itself is
 * {@link PasswordLogin}, shared with the panel.
 */
@RestController
@RequestMapping("/api/sessions")
class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);

    private static final String DEVICE_COOKIE = "SAPIENSIA_DEVICE";
    private static final Duration DEVICE_COOKIE_MAX_AGE = Duration.ofDays(365);

    private final LogoutHandler currentSessionCloser = new CompositeLogoutHandler(
            new SecurityContextLogoutHandler(), new CookieClearingLogoutHandler("JSESSIONID"));

    private final PasswordLogin passwordLogin;
    private final PasswordLogin.Variant loginVariant;
    private final CustomerService customerService;
    private final boolean secureCookie;

    SessionController(
            CustomerUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder,
            PasswordLogin passwordLogin,
            CustomerService customerService,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie) {
        this.customerService = customerService;
        this.secureCookie = secureCookie;
        this.passwordLogin = passwordLogin;
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        this.loginVariant = new PasswordLogin.Variant(
                new ProviderManager(provider),
                new ChangeSessionIdAuthenticationStrategy(),
                LoginAttemptLimiter.CUSTOMER_KEY_PREFIX,
                "customer",
                log,
                this::acceptConfirmed);
    }

    /** 204 and the session cookie, or an empty 401 that is identical for every kind of failure. */
    @PostMapping
    ResponseEntity<Void> login(
            @RequestBody LoginRequest login, HttpServletRequest request, HttpServletResponse response) {
        return passwordLogin.login(loginVariant, login.email(), login.password(), request, response);
    }

    /** Runs after a correct password: an unconfirmed account is refused only now, so it costs and answers the same. */
    private Optional<Supplier<UserDetails>> acceptConfirmed(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        var fullPrincipal = (CustomerUserDetailsService.CustomerPrincipal) authentication.getPrincipal();
        if (!fullPrincipal.emailConfirmed()) {
            return Optional.empty();
        }
        return Optional.of(() -> {
            rememberDevice(fullPrincipal.credentials().id(), request, response);
            // the session keeps the principal without the password hash
            return fullPrincipal.withoutPassword();
        });
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

    /**
     * Ends every session of the account, this one included (SEC-009, SEC-006): 204 and the cookie is deleted. The
     * current session is closed by the same Spring handlers the logout of this account uses (SecurityConfig).
     */
    @DeleteMapping
    ResponseEntity<Void> logoutEverywhere(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        var principal = (CustomerUserDetailsService.CustomerPrincipal) authentication.getPrincipal();
        customerService.logoutEverywhere(principal.credentials().id());
        currentSessionCloser.logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/current")
    Map<String, String> current(Authentication authentication) {
        return Map.of("email", authentication.getName());
    }
}
