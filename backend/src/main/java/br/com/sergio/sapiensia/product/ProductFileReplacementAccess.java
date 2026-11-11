package br.com.sergio.sapiensia.product;

import br.com.sergio.sapiensia.admin.AdministratorPrincipal;
import br.com.sergio.sapiensia.admin.Capability;
import br.com.sergio.sapiensia.admin.RecentAuthentication;
import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import br.com.sergio.sapiensia.customer.ClientIpResolver;
import br.com.sergio.sapiensia.security.SecurityConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * Who may send a file to POST /api/admin/products/{id}/file, decided before the body is read (SEC-008, ADR-0004). The
 * panel lock (PRODUCTS_FILE and both factors) comes first. A Product that was never published (or does not exist)
 * needs nothing more. One that was ever published needs a recent authentication of the session; when that is what
 * lets the request in, the request is marked {@link #REAUTHENTICATED} so the service knows. The decision is made at
 * the start of the request, so an authorized slow upload is not refused when the window ends while it is received.
 * The service checks the marker again under the row lock, which is the check that counts against a publication.
 */
@Component
public class ProductFileReplacementAccess implements AuthorizationManager<RequestAuthorizationContext> {

    /** Request attribute: the request was let in because the Administrator authenticated recently. */
    public static final String REAUTHENTICATED = ProductFileReplacementAccess.class.getName() + ".REAUTHENTICATED";

    /** Denied because a published Product's file needs a recent authentication: 403 with {@link #problem}. */
    public static final class ReauthenticationRequired extends AuthorizationDecision {
        ReauthenticationRequired() {
            super(false);
        }
    }

    /** Denied because the marker could not be read (database down): 503 without a body. */
    public static final class Unavailable extends AuthorizationDecision {
        Unavailable() {
            super(false);
        }
    }

    private final AuthorizationManager<RequestAuthorizationContext> panel =
            SecurityConfig.panel(Capability.PRODUCTS_FILE);
    private final Products products;
    private final RecentAuthentication recentAuthentication;
    private final AuditLog audit;
    private final ClientIpResolver clientIp;

    ProductFileReplacementAccess(
            Products products, RecentAuthentication recentAuthentication, AuditLog audit, ClientIpResolver clientIp) {
        this.products = products;
        this.recentAuthentication = recentAuthentication;
        this.audit = audit;
        this.clientIp = clientIp;
    }

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        AuthorizationResult panelResult = panel.authorize(authentication, context);
        if (panelResult == null || !panelResult.isGranted()) {
            return panelResult;
        }
        UUID productId = parse(context.getVariables().get("id"));
        if (productId == null) {
            return new AuthorizationDecision(true);
        }
        boolean everPublished;
        try {
            everPublished = products.everPublished(productId);
        } catch (DataAccessException | TransactionException e) {
            return new Unavailable();
        }
        if (!everPublished) {
            return new AuthorizationDecision(true);
        }
        var principal = (AdministratorPrincipal) authentication.get().getPrincipal();
        if (recentAuthentication.isRecent(principal)) {
            context.getRequest().setAttribute(REAUTHENTICATED, Boolean.TRUE);
            return new AuthorizationDecision(true);
        }
        audit.recordIndependent(new AuditEntry(
                AuditAction.ADMIN_PRODUCT_FILE_UPLOADED,
                AuditResult.BLOCKED,
                principal.id(),
                AuditObject.PRODUCT,
                productId,
                clientIp.resolve(context.getRequest())));
        return new ReauthenticationRequired();
    }

    private static UUID parse(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The 403 answer, shared by the security filter and the controller: only the code, no file or account data. */
    public static ProblemDetail problem(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Reauthentication required");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errors", Map.of("reauthentication", "REAUTHENTICATION_REQUIRED"));
        return problem;
    }
}
