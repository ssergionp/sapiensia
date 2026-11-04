package br.com.sergio.sapiensia.customer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer login and "who am I". Logout is the DELETE rule in SecurityConfig. */
@RestController
@RequestMapping("/api/sessions")
class SessionController {

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionStrategy = new ChangeSessionIdAuthenticationStrategy();
    private final CsrfTokenRepository csrfTokenRepository;
    private final HttpSessionSecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private final LoginAttemptLimiter limiter;
    private final ClientIpResolver clientIp;

    SessionController(
            CustomerUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder,
            CsrfTokenRepository csrfTokenRepository,
            LoginAttemptLimiter limiter,
            ClientIpResolver clientIp) {
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
        // before any authentication: a locked account refuses even the correct password, and no hash is computed
        Optional<Duration> blocked = limiter.blockedFor(login.email(), clientIp.resolve(request));
        if (blocked.isPresent()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(
                            HttpHeaders.RETRY_AFTER,
                            String.valueOf(blocked.get().toSeconds()))
                    .build();
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(login.email(), login.password()));
        } catch (AuthenticationException e) {
            limiter.recordFailure(login.email());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        CustomerUserDetailsService.CustomerPrincipal fullPrincipal =
                (CustomerUserDetailsService.CustomerPrincipal) authentication.getPrincipal();
        // checked after the password, so a wrong password and an unconfirmed account cost and answer the same
        if (!fullPrincipal.emailConfirmed()) {
            limiter.recordFailure(login.email());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        limiter.recordSuccess(login.email());
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

    @GetMapping("/current")
    Map<String, String> current(Authentication authentication) {
        return Map.of("email", authentication.getName());
    }
}
