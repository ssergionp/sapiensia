package br.com.sergio.sapiensia.admin;

/** What the startup bootstrap did. */
public enum BootstrapResult {
    CREATED,
    PENDING_EMAIL_SCHEDULED,
    PENDING_RECENTLY_SENT,
    EXISTS,
    CONFIG_DIFFERS,
    SKIPPED_NOT_CONFIGURED
}
