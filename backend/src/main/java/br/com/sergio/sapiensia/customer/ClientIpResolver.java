package br.com.sergio.sapiensia.customer;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Finds the client IP for login limiting. Only the header named in app.login-limit.client-ip-header is trusted
 * (the platform proxy sets it); when it is not configured, any such header is ignored because it could be forged.
 * The header must only be set to one that the platform proxy overwrites (e.g. Fly-Client-IP), never to
 * X-Forwarded-For. A value that is not a literal IP address, an absent header or no configured header falls back
 * to the real connection address recorded by ConnectionAddressRecorder before any forwarded-header rewriting
 * (getRemoteAddr() when none was recorded). X-Forwarded-For is never used.
 */
@Component
public class ClientIpResolver {

    private static final int MAX_IP_LENGTH = 45;
    private static final Pattern IPV4 =
            Pattern.compile("(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

    private final String headerName;

    ClientIpResolver(LoginLimitProperties properties) {
        this.headerName = properties.clientIpHeader();
    }

    public String resolve(HttpServletRequest request) {
        if (headerName != null && !headerName.isBlank()) {
            String value = request.getHeader(headerName);
            if (value != null && !value.isBlank()) {
                String candidate = value.split(",")[0].trim();
                String canonical = canonicalLiteralIp(candidate);
                if (canonical != null) {
                    return canonical;
                }
            }
        }
        return connectionAddress(request);
    }

    /** The address recorded before any forwarded-header rewriting; getRemoteAddr() only when nothing was recorded. */
    private static String connectionAddress(HttpServletRequest request) {
        Object recorded = request.getAttribute(ConnectionAddressRecorder.ATTRIBUTE);
        return recorded instanceof String address ? address : request.getRemoteAddr();
    }

    /**
     * Returns the canonical text of a literal IPv4/IPv6 address (so equivalent forms share one limiter key), or
     * null when the value is not a literal. Never performs a DNS lookup.
     */
    private static String canonicalLiteralIp(String candidate) {
        if (candidate.isEmpty() || candidate.length() > MAX_IP_LENGTH) {
            return null;
        }
        if (!IPV4.matcher(candidate).matches()) {
            if (candidate.indexOf(':') < 0 || !IPV6_CHARS.matcher(candidate).matches()) {
                return null;
            }
            // getByName only parses a literal when the string starts with a hex digit or ':'; anything else
            // (e.g. ".:1") would go through the DNS resolver.
            char first = candidate.charAt(0);
            if (first != ':' && Character.digit(first, 16) < 0) {
                return null;
            }
        }
        try {
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
