package br.com.sergio.sapiensia.payment;

/** What processing a payment event ended with. GAVE_UP is the only result that is not final. */
enum PaymentResult {
    PAID,
    ALREADY_PAID,
    DUPLICATE_PURCHASE,
    NO_ORDER,
    INVALID_PAYLOAD,
    CHARGE_NOT_FOUND,
    CHARGE_ID_MISMATCH,
    REFERENCE_MISMATCH,
    AMOUNT_MISMATCH,
    NOT_PIX,
    NOT_PAID,
    GAVE_UP;

    /** True when the Administrator has to look at the event. */
    boolean needsReview() {
        return this != PAID && this != ALREADY_PAID && this != NO_ORDER;
    }

    boolean isFinal() {
        return this != GAVE_UP;
    }
}
