package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundWindowTest {

    @Test
    @DisplayName("FR-023: o prazo vai até o fim do 7º dia depois do pagamento, em São Paulo")
    void shouldCloseAtTheStartOfTheEighthDayInSaoPaulo() {
        // 19/11 23:30 in São Paulo
        assertThat(RefundWindow.closesAt(Instant.parse("2026-11-20T02:30:00Z")))
                .isEqualTo(Instant.parse("2026-11-27T03:00:00Z"));
        assertThat(RefundWindow.lastDay(Instant.parse("2026-11-20T02:30:00Z"))).isEqualTo(LocalDate.of(2026, 11, 26));
        // 20/11 00:00 in São Paulo
        assertThat(RefundWindow.closesAt(Instant.parse("2026-11-20T03:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-28T03:00:00Z"));
    }

    @Test
    @DisplayName("FR-023: 23h59m59 do 7º dia ainda está no prazo; 00h00 do 8º dia já não está")
    void shouldAcceptTheLastSecondOfTheSeventhDayAndRefuseTheEighthDay() {
        // paid on 19/11 at 10:00 in São Paulo; the 7th day after it is 26/11
        Instant closesAt = RefundWindow.closesAt(Instant.parse("2026-11-19T13:00:00Z"));

        // 26/11 23:59:59 in São Paulo
        assertThat(Instant.parse("2026-11-27T02:59:59Z").isBefore(closesAt)).isTrue();
        // 27/11 00:00:00 in São Paulo
        assertThat(Instant.parse("2026-11-27T03:00:00Z").isBefore(closesAt)).isFalse();
    }
}
