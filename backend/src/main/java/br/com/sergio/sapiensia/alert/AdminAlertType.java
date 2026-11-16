package br.com.sergio.sapiensia.alert;

/** The closed list of alerts, in priority order: when the hourly limit tightens, the first ones go out first. */
public enum AdminAlertType {
    PAYMENT_EVENT_GAVE_UP,
    PAYMENT_EVENT_NEEDS_REVIEW,
    ORDER_PAID_EMAIL_GAVE_UP,
    WEBHOOK_REJECTED,
    TASK_GAVE_UP
}
