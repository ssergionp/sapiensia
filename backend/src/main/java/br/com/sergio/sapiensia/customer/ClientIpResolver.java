package br.com.sergio.sapiensia.customer;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Finds the client IP for login limiting. Only the header named in app.login-limit.client-ip-header is trusted
 * (the platform proxy sets it); when it is not configured, any such header is ignored because it could be forged.
 */
@Component
class ClientIpResolver {

    private final String headerName;

    ClientIpResolver(LoginLimitProperties properties) {
        this.headerName = properties.clientIpHeader();
    }

    String resolve(HttpServletRequest request) {
        if (headerName != null && !headerName.isBlank()) {
            String value = request.getHeader(headerName);
            if (value != null && !value.isBlank()) {
                return value.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
