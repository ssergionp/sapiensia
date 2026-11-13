package br.com.sergio.sapiensia.demo;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * The fictitious accounts of the local demo (scripts/demo.ps1 prints the same values). They exist only where the demo
 * seeders run: the local profile with app.demo.enabled=true. Never real credentials.
 */
public final class DemoAccounts {

    public static final String ADMIN_EMAIL = "admin@example.com";
    public static final String ADMIN_PASSWORD = "Demo-Admin-2026!"; // gitleaks:allow
    public static final String CUSTOMER_EMAIL = "cliente@example.com";
    public static final String CUSTOMER_PASSWORD = "Demo-Cliente-2026!"; // gitleaks:allow
    /** Base32 of the Administrator's TOTP secret (20 bytes), typed once into the authenticator app. */
    // nosemgrep: generic.secrets.security.detected-generic-secret.detected-generic-secret -- fake demo key
    public static final String ADMIN_TOTP_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"; // gitleaks:allow

    private DemoAccounts() {}

    /** Third lock against the prod profile: even if both profiles are active together, the application does not start. */
    public static void refuseInProd(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("demo data is never allowed in the prod profile");
        }
    }
}
