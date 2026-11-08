package br.com.sergio.sapiensia.audit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audit settings (app.audit.*). The retention cannot be below the 365-day floor that the database trigger enforces
 * (V10 migration); a smaller value stops the application instead of failing every night.
 */
@ConfigurationProperties("app.audit")
public record AuditProperties(Duration retention) {

    public static final Duration MIN_RETENTION = Duration.ofDays(365);

    public AuditProperties {
        if (retention == null || retention.compareTo(MIN_RETENTION) < 0) {
            throw new IllegalArgumentException("app.audit.retention must be at least " + MIN_RETENTION);
        }
    }
}
