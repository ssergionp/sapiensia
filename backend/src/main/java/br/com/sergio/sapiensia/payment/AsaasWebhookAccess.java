package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.alert.AdminAlertType;
import br.com.sergio.sapiensia.alert.AdminAlerts;
import br.com.sergio.sapiensia.customer.ClientIpResolver;
import br.com.sergio.sapiensia.web.RefusalLogThrottle;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Who may call the Asaas webhook: the source IP must be one of the official addresses AND the asaas-access-token header
 * must carry the shared secret. There is no session or user: the two checks are the whole credential (SEC-023). Both
 * are always evaluated, so the refusal log says exactly what failed. The token is compared by SHA-256, so the
 * comparison time depends neither on how much of the token matched nor on its length.
 */
@Component
public class AsaasWebhookAccess implements AuthorizationManager<RequestAuthorizationContext> {

    public static final String TOKEN_HEADER = "asaas-access-token";

    private static final Logger log = LoggerFactory.getLogger(AsaasWebhookAccess.class);

    private final ClientIpResolver clientIpResolver;
    private final Set<String> allowedIps;
    private final byte[] expectedSha256;
    private final AdminAlerts alerts;
    private final RefusalLogThrottle refusalLog;

    AsaasWebhookAccess(
            ClientIpResolver clientIpResolver,
            AsaasWebhookProperties properties,
            AdminAlerts alerts,
            RefusalLogThrottle refusalLog) {
        this.refusalLog = refusalLog;
        this.clientIpResolver = clientIpResolver;
        this.alerts = alerts;
        this.allowedIps = properties.canonicalAllowedIps();
        this.expectedSha256 = sha256(properties.token());
    }

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        HttpServletRequest request = context.getRequest();
        String ip = clientIpResolver.resolve(request);
        String token = request.getHeader(TOKEN_HEADER);
        // no short-circuit: both checks always run
        boolean ipAllowed = allowedIps.contains(ip);
        boolean tokenValid = token != null && MessageDigest.isEqual(expectedSha256, sha256(token));
        if (!ipAllowed || !tokenValid) {
            String reason = !ipAllowed && !tokenValid
                    ? "IP_NOT_ALLOWED_AND_TOKEN_INVALID"
                    : !ipAllowed ? "IP_NOT_ALLOWED" : "TOKEN_INVALID";
            // sampled per reason: anyone can repeat a refusal in a loop (the decision and the alert are not sampled)
            refusalLog
                    .pass("asaas-webhook:" + reason)
                    .ifPresent(
                            held -> log.warn("Asaas webhook refused: ip={} reason={} suppressed={}", ip, reason, held));
            if (ipAllowed != tokenValid) {
                // exactly one credential matches: a swapped token or a new Asaas address. Both wrong is just noise.
                alerts.recordSafely(AdminAlertType.WEBHOOK_REJECTED, ipAllowed ? "TOKEN_INVALID" : "IP_NOT_ALLOWED");
            }
        }
        return new AuthorizationDecision(ipAllowed && tokenValid);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
