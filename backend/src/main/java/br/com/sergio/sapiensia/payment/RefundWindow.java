package br.com.sergio.sapiensia.payment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The Refund window (FR-023): until the end of the 7th day after the payment, on the São Paulo calendar. A request is
 * accepted while {@code now} is before {@link #closesAt}.
 */
final class RefundWindow {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private RefundWindow() {}

    /** The last day a request is accepted: the payment day plus 7, in São Paulo. */
    static LocalDate lastDay(Instant paidAt) {
        return LocalDate.ofInstant(paidAt, ZONE).plusDays(7);
    }

    /** The first instant a request is refused: the start of the day after {@link #lastDay}, in São Paulo. */
    static Instant closesAt(Instant paidAt) {
        return lastDay(paidAt).plusDays(1).atStartOfDay(ZONE).toInstant();
    }
}
