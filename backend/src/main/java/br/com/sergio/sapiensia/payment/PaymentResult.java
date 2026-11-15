package br.com.sergio.sapiensia.payment;

/** What processing a payment event ended with. GAVE_UP is the only result that is not final. */
enum PaymentResult {
    PAID,
    ALREADY_PAID,
    DUPLICATE_PURCHASE,
    NO_ORDER,
    INVALID_PAYLOAD,
    /** The provider answered 404 for the charge; other 4xx answers are retried as UNAVAILABLE. */
    CHARGE_NOT_FOUND,
    CHARGE_ID_MISMATCH,
    REFERENCE_MISMATCH,
    AMOUNT_MISMATCH,
    NOT_PIX,
    NOT_PAID,
    /** A refund the provider confirmed: the Order is REFUNDED and its Access was cancelled. */
    REFUNDED,
    /** The Order was already REFUNDED, so there is nothing to pay or to cancel. */
    ALREADY_REFUNDED,
    /** A refund notice, but the provider still shows the charge as not refunded. */
    NOT_REFUNDED,
    /** A partial refund: the charge is not (yet) refunded in full, so the Access stays. */
    PARTIAL_REFUND,
    /** A Pix infraction (MED) block, the charge not refunded: only recorded, the Access stays. */
    MED_BLOCKED,
    /** A Pix infraction (MED) block without the id of a charge. */
    MED_WITHOUT_CHARGE,
    /** A balance block that is not a Pix infraction: only recorded. */
    BALANCE_BLOCKED,
    GAVE_UP;

    /** True when the Administrator has to look at the event. */
    boolean needsReview() {
        return this != PAID && this != ALREADY_PAID && this != NO_ORDER && this != REFUNDED && this != ALREADY_REFUNDED;
    }

    boolean isFinal() {
        return this != GAVE_UP;
    }
}
