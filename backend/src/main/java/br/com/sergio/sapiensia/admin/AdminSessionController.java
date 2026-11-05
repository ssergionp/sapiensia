package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.customer.ClientIpResolver;
import br.com.sergio.sapiensia.customer.LoginAttemptLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrator login and "who am I" of the panel. Logout is the existing {@code DELETE /api/sessions/current}. */
@RestController
@RequestMapping("/api/admin/sessions")
class AdminSessionController {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionController.class);

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_PASSWORD_LENGTH = 128;
    // separate limiter keys from the customer's, so one kind of failure never locks the other
    private static final String LIMITER_PREFIX = "admin:";

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final CsrfTokenRepository csrfTokenRepository;
    private final HttpSessionSecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();
    private final LoginAttemptLimiter limiter;
    private final ClientIpResolver clientIp;

    AdminSessionController(
            Administrators administrators,
            PasswordEncoder passwordEncoder,
            CsrfTokenRepository csrfTokenRepository,
            LoginAttemptLimiter limiter,
            ClientIpResolver clientIp) {
        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(new AdministratorUserDetailsService(administrators));
        provider.setPasswordEncoder(passwordEncoder);
        this.authenticationManager = new ProviderManager(provider);
        // the whole session is replaced: new id and none of the previous attributes (a Customer who was logged in
        // at this browser is logged out)
        SessionFixationProtectionStrategy strategy = new SessionFixationProtectionStrategy();
        strategy.setMigrateSessionAttributes(false);
        this.sessionStrategy = strategy;
        this.csrfTokenRepository = csrfTokenRepository;
        this.limiter = limiter;
        this.clientIp = clientIp;
    }

    /** 204 and the session cookie, or an empty 401 that is identical for every kind of failure. */
    @PostMapping
    ResponseEntity<Void> login(
            @RequestBody AdminLoginRequest login, HttpServletRequest request, HttpServletResponse response) {
        // such inputs can never match an account: refuse them without touching the limiter and without hashing
        if (login.email() == null
                || login.email().isBlank()
                || login.password() == null
                || login.email().length() > MAX_EMAIL_LENGTH
                || login.password().length() > MAX_PASSWORD_LENGTH) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String limiterKey = LIMITER_PREFIX + login.email();
        LoginAttemptLimiter.Admission admission = limiter.admit(limiterKey, LIMITER_PREFIX + clientIp.resolve(request));
        if (!admission.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(
                            HttpHeaders.RETRY_AFTER,
                            String.valueOf(admission.blockedFor().toSeconds()))
                    .build();
        }
        Authentication authentication;
        AdministratorPrincipal principal;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(login.email(), login.password()));
            principal = ((AdministratorUserDetailsService.Loaded) authentication.getPrincipal()).principal();
        } catch (InternalAuthenticationServiceException e) {
            // the lookup failed (database down): not a wrong password, so it is not counted, and not answered as one.
            // Only the class of the cause is logged: its message could carry the e-mail.
            limiter.release(limiterKey, admission);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.error(
                    "Panel login unavailable: the administrator lookup failed ({})",
                    cause.getClass().getName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (AuthenticationException e) {
            // a wrong password keeps the failure that was reserved for it
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (RuntimeException e) {
            limiter.release(limiterKey, admission);
            throw e;
        }
        limiter.recordSuccess(limiterKey);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        sessionStrategy.onAuthentication(authentication, request, response);
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
        securityContextRepository.saveContext(context, request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/current")
    Map<String, Object> current(Authentication authentication) {
        var principal = (AdministratorPrincipal) authentication.getPrincipal();
        List<String> capabilities = principal.capabilities().stream()
                .sorted(Comparator.naturalOrder())
                .map(Capability::name)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", principal.email());
        body.put("capabilities", capabilities);
        return body;
    }
}
