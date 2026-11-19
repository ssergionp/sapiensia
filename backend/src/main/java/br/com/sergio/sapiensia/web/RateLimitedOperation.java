package br.com.sergio.sapiensia.web;

/** The operations that have a request limit (SEC-014). Login is not here: it has its own limiter (ADR-0004). */
public enum RateLimitedOperation {
    ORDER_CREATE,
    ORDER_READ,
    DOWNLOAD,
    COVER,
    SIGN_UP,
    PASSWORD_RESET_REQUEST,
    PASSWORD_RESET_CONFIRM,
    EMAIL_CONFIRMATION,
    ADMIN_PASSWORD_SETUP,
    REFUND_REQUEST
}
