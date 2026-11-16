package br.com.sergio.sapiensia.customer;

import br.com.sergio.sapiensia.admin.AdministratorPrincipal;
import br.com.sergio.sapiensia.admin.Capability;
import br.com.sergio.sapiensia.admin.RecentAuthentication;
import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditResult;
import br.com.sergio.sapiensia.security.SecurityConfig;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Who may call POST /api/admin/customer-anonymizations (FR-036, SEC-038): the panel lock (CUSTOMERS_ANONYMIZE and both
 * factors) and, always, a recent authentication, because the operation cannot be undone. Like
 * ProductFileReplacementAccess, the denial for an old authentication is a 403 with the REAUTHENTICATION_REQUIRED body.
 */
@Component
public class CustomerAnonymizationAccess implements AuthorizationManager<RequestAuthorizationContext> {

    /** Denied because the last authentication is too old: 403 with the REAUTHENTICATION_REQUIRED body. */
    public static final class ReauthenticationRequired extends AuthorizationDecision {
        ReauthenticationRequired() {
            super(false);
        }
    }

    private final AuthorizationManager<RequestAuthorizationContext> panel =
            SecurityConfig.panel(Capability.CUSTOMERS_ANONYMIZE);
    private final RecentAuthentication recentAuthentication;
    private final AuditLog audit;
    private final ClientIpResolver clientIp;

    CustomerAnonymizationAccess(RecentAuthentication recentAuthentication, AuditLog audit, ClientIpResolver clientIp) {
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
        var principal = (AdministratorPrincipal) authentication.get().getPrincipal();
        if (recentAuthentication.isRecent(principal)) {
            return new AuthorizationDecision(true);
        }
        audit.recordIndependent(new AuditEntry(
                AuditAction.ADMIN_CUSTOMER_ANONYMIZED,
                AuditResult.BLOCKED,
                principal.id(),
                null,
                null,
                clientIp.resolve(context.getRequest())));
        return new ReauthenticationRequired();
    }
}
