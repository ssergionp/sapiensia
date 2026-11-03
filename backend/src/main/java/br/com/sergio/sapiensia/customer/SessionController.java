package br.com.sergio.sapiensia.customer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
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

    SessionController(
            CustomerUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder,
            CsrfTokenRepository csrfTokenRepository) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        this.authenticationManager = new ProviderManager(provider);
        this.csrfTokenRepository = csrfTokenRepository;
    }

    /** 204 and the session cookie, or an empty 401 that is identical for every kind of failure. */
    @PostMapping
    ResponseEntity<Void> login(
            @RequestBody LoginRequest login, HttpServletRequest request, HttpServletResponse response) {
        if (login.email() == null || login.email().isBlank() || login.password() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(login.email(), login.password()));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // checked after the password, so a wrong password and an unconfirmed account cost and answer the same
        if (!((CustomerUserDetailsService.CustomerPrincipal) authentication.getPrincipal()).emailConfirmed()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // the session keeps the principal without the password hash
        CustomerUserDetailsService.CustomerPrincipal principal =
                ((CustomerUserDetailsService.CustomerPrincipal) authentication.getPrincipal()).withoutPassword();
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
