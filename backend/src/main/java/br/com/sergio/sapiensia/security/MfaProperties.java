package br.com.sergio.sapiensia.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MFA settings (app.mfa.*). The key is a secret: it never appears in toString(), logs or messages. */
@ConfigurationProperties("app.mfa")
public record MfaProperties(String encryptionKey) {

    @Override
    public String toString() {
        return "MfaProperties[redacted]";
    }
}
