package br.com.sergio.sapiensia.alert;

/** The closed list of alerts, in priority order: when the hourly limit tightens, the first ones go out first. */
public enum AdminAlertType {
    PAYMENT_EVENT_GAVE_UP,
    PAYMENT_EVENT_NEEDS_REVIEW,
    RECONCILIATION_DIVERGENCE,
    ORDER_PAID_EMAIL_GAVE_UP,
    ORDER_REFUNDED_EMAIL_GAVE_UP,
    WEBHOOK_REJECTED,
    RECONCILIATION_FAILED,
    TASK_GAVE_UP,
    RECURRING_TASK_FAILING
}
