package br.com.sergio.sapiensia.customer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * The login procedure shared by the customer and the panel: input guards, limiter admission (429), the password check
 * with its three outcomes (wrong password 401, lookup failure 503, unexpected error), and, on success, the new
 * session id, the new CSRF token and the saved security context. Each kind of account supplies a {@link Variant}.
 */
@Component
public class PasswordLogin {

    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_PASSWORD_LENGTH = 128;

    /**
     * What runs after a correct password: empty refuses with the usual 401 (e.g. e-mail not confirmed); otherwise the
     * supplier is called once the attempt is recorded as a success and gives the principal to keep in the session.
     */
    @FunctionalInterface
    public interface AfterPassword {
        Optional<Supplier<UserDetails>> accept(
                Authentication authentication, HttpServletRequest request, HttpServletResponse response);
    }

    /**
     * How one kind of account logs in. {@code accountKey} turns the typed e-mail into the limiter's account key, with a
     * prefix of its own and the e-mail normalized ({@link LoginAttemptLimiter#customerKey} for customers, "admin:" for
     * the panel); {@code subject} and {@code log} are only for the 503 log line.
     */
    public record Variant(
            AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionStrategy,
            UnaryOperator<String> accountKey,
            String subject,
            Logger log,
            AfterPassword afterPassword) {}

    private final LoginAttemptLimiter limiter;
    private final ClientIpResolver clientIp;
    private final CsrfTokenRepository csrfTokenRepository;
    private final HttpSessionSecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    PasswordLogin(LoginAttemptLimiter limiter, ClientIpResolver clientIp, CsrfTokenRepository csrfTokenRepository) {
        this.limiter = limiter;
        this.clientIp = clientIp;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    /** 204 and the session cookie, or an empty 401 that is identical for every kind of failure. */
    public ResponseEntity<Void> login(
            Variant variant, String email, String password, HttpServletRequest request, HttpServletResponse response) {
        // such inputs can never match an account: refuse them without touching the limiter and without hashing
        if (email == null
                || email.isBlank()
                || password == null
                || email.length() > MAX_EMAIL_LENGTH
                || password.length() > MAX_PASSWORD_LENGTH) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // normalized before the prefix, like the account lookup: " Ana@x" must not get a bucket of its own. The IP
        // key has no prefix: the per-IP budget is shared by the customer and the panel logins.
        String accountKey = variant.accountKey().apply(email);
        // before any authentication: a locked account refuses even the correct password, and no hash is computed.
        // An admitted attempt is already counted as a failure (see LoginAttemptLimiter), so parallel requests cannot
        // all get past the check before the first failure is recorded.
        LoginAttemptLimiter.Admission admission = limiter.admit(accountKey, clientIp.resolve(request));
        if (!admission.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(
                            HttpHeaders.RETRY_AFTER,
                            String.valueOf(admission.blockedFor().toSeconds()))
                    .build();
        }
        Authentication authentication;
        try {
            authentication = variant.authenticationManager()
                    .authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        } catch (InternalAuthenticationServiceException e) {
            // Spring wraps a failure of the user lookup (database down) in this AuthenticationException: it is not
            // a wrong password, so it must not count against the account, and the answer must not say it was one.
            // Answered here with 503 because a rethrown AuthenticationException would be turned into a 401 by Spring
            // Security. Only the class of the cause is logged: its message could carry the e-mail.
            limiter.release(accountKey, admission);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            variant.log()
                    .error(
                            "Login unavailable: the {} lookup failed ({})",
                            variant.subject(),
                            cause.getClass().getName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (AuthenticationException e) {
            // a wrong password keeps the failure that was reserved for it
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (RuntimeException e) {
            // an unexpected error is not a failure of the account: give the reserved place back
            limiter.release(accountKey, admission);
            throw e;
        }
        // checked after the password, so a wrong password and a refused account cost and answer the same
        Optional<Supplier<UserDetails>> accepted = variant.afterPassword().accept(authentication, request, response);
        if (accepted.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        limiter.recordSuccess(accountKey);
        UserDetails principal = accepted.get().get();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        // New session id and new CSRF token at login (session fixation). The token is issued here because
        // CsrfAuthenticationStrategy only expires the old cookie, so the SPA would have no valid cookie for logout.
        variant.sessionStrategy().onAuthentication(authentication, request, response);
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
        securityContextRepository.saveContext(context, request, response);
        return ResponseEntity.noContent().build();
    }
}
