package br.com.sergio.sapiensia.audit;

/** What happened. Closed list: the database accepts only upper-case words, so free text cannot get in. */
public enum AuditAction {
    ADMIN_PASSWORD_LOGIN,
    ADMIN_MFA_LOGIN,
    ADMIN_MFA_ENABLED,
    ADMIN_RECOVERY_CODE_LOGIN,
    ADMIN_RECOVERY_CODES_REGENERATED,
    ADMIN_PASSWORD_SET
}
