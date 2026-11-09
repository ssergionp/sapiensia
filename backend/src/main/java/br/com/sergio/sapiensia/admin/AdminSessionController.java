package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import br.com.sergio.sapiensia.customer.ClientIpResolver;
import br.com.sergio.sapiensia.customer.Emails;
import br.com.sergio.sapiensia.customer.PasswordLogin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator login, first step (password, which leaves the session "MFA pending"), and "who am I" of the panel. The
 * second step is AdminMfaController. Logout is the existing {@code DELETE /api/sessions/current}.
 */
@RestController
@RequestMapping("/api/admin/sessions")
class AdminSessionController {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionController.class);

    // separate account keys from the customer's, so one kind of failure never locks the other; the IP bucket is shared
    private static final String ACCOUNT_KEY_PREFIX = "admin:";

    private final PasswordLogin passwordLogin;
    private final PasswordLogin.Variant loginVariant;
    private final AuditLog audit;
    private final ClientIpResolver clientIp;
    private final TransactionTemplate transaction;
    private final Clock clock;

    AdminSessionController(
            Administrators administrators,
            PasswordEncoder passwordEncoder,
            PasswordLogin passwordLogin,
            AuditLog audit,
            ClientIpResolver clientIp,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.passwordLogin = passwordLogin;
        this.audit = audit;
        this.clientIp = clientIp;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(new AdministratorUserDetailsService(administrators));
        provider.setPasswordEncoder(passwordEncoder);
        this.loginVariant = new PasswordLogin.Variant(
                new ProviderManager(provider),
                panelSessionStrategy(),
                AdminSessionController::accountKey,
                "administrator",
                log,
                this::accept,
                // nobody is identified: the typed e-mail may be a Customer's, or even the password in the wrong field
                request -> audit.recordIndependent(new AuditEntry(
                        AuditAction.ADMIN_PASSWORD_LOGIN,
                        AuditResult.FAILURE,
                        null,
                        null,
                        null,
                        clientIp.resolve(request))));
    }

    /**
     * The session strategy of both panel steps (password and code), built only here. The session id is replaced; only
     * Spring Security's own SPRING_SECURITY_* attributes are still copied, and the security context is replaced right
     * after by saveContext (a Customer or a pending Administrator at this browser is logged out); CSRF lives in a
     * cookie, not in the session.
     */
    static SessionAuthenticationStrategy panelSessionStrategy() {
        SessionFixationProtectionStrategy strategy = new SessionFixationProtectionStrategy();
        strategy.setMigrateSessionAttributes(false);
        return strategy;
    }

    /** The e-mail normalized, then the "admin:" prefix: every spelling of one e-mail shares one bucket. */
    private static String accountKey(String email) {
        return ACCOUNT_KEY_PREFIX + Emails.normalize(email);
    }

    /**
     * 204 and a session in the "MFA pending" state (no panel access yet), or an empty 401 that is identical for every
     * kind of failure. Called again from a full session, it starts over: the session goes back to pending.
     */
    @PostMapping
    ResponseEntity<Void> login(
            @RequestBody AdminLoginRequest login, HttpServletRequest request, HttpServletResponse response) {
        return passwordLogin.login(loginVariant, login.email(), login.password(), request, response);
    }

    Optional<Supplier<UserDetails>> accept(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        var loaded = (AdministratorUserDetailsService.Loaded) authentication.getPrincipal();
        // the step writes nothing else, so its event gets a short transaction of its own, before the pending session
        // is opened: without the trail there is no session (503)
        try {
            transaction.executeWithoutResult(status -> audit.recordInTransaction(new AuditEntry(
                    AuditAction.ADMIN_PASSWORD_LOGIN,
                    AuditResult.SUCCESS,
                    loaded.id(),
                    AuditObject.ADMINISTRATOR,
                    loaded.id(),
                    clientIp.resolve(request))));
        } catch (DataAccessException | TransactionException | IllegalStateException e) {
            log.error("Login unavailable: the audit failed ({})", e.getClass().getName());
            throw new AuditUnavailableException();
        }
        return Optional.of(
                () -> new PendingAdministrator(loaded.id(), loaded.email(), loaded.sessionVersion(), clock.instant()));
    }

    @ExceptionHandler(AuditUnavailableException.class)
    ResponseEntity<Void> auditUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
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
