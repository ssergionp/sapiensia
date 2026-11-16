package br.com.sergio.sapiensia.payment;

/** The closed list of differences the nightly reconciliation reports (ADR-0002). */
enum ReconciliationDivergence {
    CHARGE_WITHOUT_ORDER,
    ORDER_PAID_CHARGE_NOT_PAID,
    ORDER_PAID_CHARGE_NOT_FOUND,
    ORDER_REFUNDED_CHARGE_PAID
}
