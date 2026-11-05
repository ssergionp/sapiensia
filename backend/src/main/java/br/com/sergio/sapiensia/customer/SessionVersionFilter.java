package br.com.sergio.sapiensia.customer;

import br.com.sergio.sapiensia.admin.AdministratorPrincipal;
import br.com.sergio.sapiensia.admin.AdministratorSessions;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a customer or Administrator session opened before the last password change (SEC-006). The session keeps the
 * session version read at login; a password change increments it in the database, so an older session no longer
 * matches. Such a session is invalidated and the request goes on as anonymous, so protected routes answer 401.
 *
 * <p>Runs inside the Spring Security chain, right after the context is loaded from the session (see SecurityConfig);
 * it is public only so SecurityConfig can register it.
 */
@Component
public class SessionVersionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionVersionFilter.class);

    private final CustomerRepository customers;
    private final AdministratorSessions administratorSessions;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    SessionVersionFilter(CustomerRepository customers, AdministratorSessions administratorSessions) {
        this.customers = customers;
        this.administratorSessions = administratorSessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = contextHolder.getContext().getAuthentication();
        Object who = authentication == null ? null : authentication.getPrincipal();
        if (who instanceof CustomerUserDetailsService.CustomerPrincipal || who instanceof AdministratorPrincipal) {
            int sessionVersion;
            Optional<Integer> current;
            try {
                if (who instanceof AdministratorPrincipal admin) {
                    sessionVersion = admin.sessionVersion();
                    OptionalInt version = administratorSessions.findSessionVersion(admin.id());
                    current = version.isPresent() ? Optional.of(version.getAsInt()) : Optional.empty();
                } else {
                    var customer = (CustomerUserDetailsService.CustomerPrincipal) who;
                    sessionVersion = customer.credentials().sessionVersion();
                    current =
                            customers.findSessionVersion(customer.credentials().id());
                }
            } catch (DataAccessException | TransactionException e) {
                // fail closed: a session that cannot be checked is not trusted. Only the class is logged, since the
                // message could carry personal data.
                log.error(
                        "Session check unavailable: the session version lookup failed ({})",
                        e.getClass().getName());
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                return;
            }
            if (current.filter(v -> v == sessionVersion).isEmpty()) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                // nothing saves the context back on its own (explicit save), and the session is gone anyway
                contextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
