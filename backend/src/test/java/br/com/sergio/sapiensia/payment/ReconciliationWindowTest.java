package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.sergio.sapiensia.alert.AdminAlertType;
import br.com.sergio.sapiensia.alert.AdminAlerts;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * FR-022: the reconciliation window is counted in São Paulo calendar days, not in UTC days (a plain unit test with a
 * fixed clock; no Spring context).
 */
@ExtendWith(OutputCaptureExtension.class)
class ReconciliationWindowTest {

    // 2026-11-16T02:30:00Z is 15/11/2026 23:30 in São Paulo (UTC-3)
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T02:30:00Z"), ZoneOffset.UTC);

    private final PixGateway gateway = mock(PixGateway.class);
    private final Orders orders = mock(Orders.class);

    private Reconciliation reconciliation(int windowDays, int maxPages, boolean endless) {
        when(gateway.listCharges(any(), anyInt(), anyInt())).thenReturn(new PixChargePage(List.of(), endless));
        when(orders.findPaidCreatedSince(any())).thenReturn(List.of());
        return new Reconciliation(
                gateway,
                orders,
                mock(PaymentEvents.class),
                mock(PaymentEventResults.class),
                mock(ReconciliationDivergences.class),
                mock(AdminAlerts.class),
                CLOCK,
                null,
                mock(PlatformTransactionManager.class),
                windowDays,
                maxPages);
    }

    @Test
    @DisplayName("FR-022: a janela começa 90 dias antes do dia de São Paulo, à meia-noite de São Paulo")
    void shouldStartTheWindowAtTheSaoPauloDay() {
        reconciliation(90, 300, false).run();

        ArgumentCaptor<LocalDate> createdFrom = ArgumentCaptor.forClass(LocalDate.class);
        verify(gateway).listCharges(createdFrom.capture(), anyInt(), anyInt());
        assertThat(createdFrom.getValue()).isEqualTo(LocalDate.of(2026, 8, 17));
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(orders).findPaidCreatedSince(since.capture());
        assertThat(since.getValue()).isEqualTo(Instant.parse("2026-08-17T03:00:00Z"));
    }

    @Test
    @DisplayName("SEC-030: ao passar de max-pages o log diz o índice (base 0) da última página pedida")
    void shouldLogTheIndexOfTheLastRequestedPageWhenThereAreTooManyCharges(CapturedOutput output) {
        Reconciliation reconciliation = reconciliation(90, 3, true);

        assertThatThrownBy(reconciliation::run).isInstanceOf(Reconciliation.IncompleteException.class);

        assertThat(output.getAll()).contains("Reconciliation incomplete: reason=TOO_MANY_CHARGES page=2");
    }

    @Test
    @DisplayName("SEC-030: falha inesperada no meio da conciliação alerta o Administrador")
    void shouldAlertWhenTheRunStopsOnAnUnexpectedError(CapturedOutput output) {
        PixCharge paid = new PixCharge(
                "pay_abc123",
                "RECEIVED",
                new BigDecimal("29.90"),
                "PIX",
                UUID.randomUUID().toString(),
                false);
        when(gateway.listCharges(any(), anyInt(), anyInt())).thenReturn(new PixChargePage(List.of(paid), false));
        IllegalStateException failure = new IllegalStateException("db down");
        when(orders.findByProviderPaymentId("pay_abc123")).thenThrow(failure);
        AdminAlerts alerts = mock(AdminAlerts.class);
        Reconciliation reconciliation = new Reconciliation(
                gateway,
                orders,
                mock(PaymentEvents.class),
                mock(PaymentEventResults.class),
                mock(ReconciliationDivergences.class),
                alerts,
                CLOCK,
                null,
                mock(PlatformTransactionManager.class),
                90,
                300);

        assertThatThrownBy(reconciliation::run).isInstanceOfSatisfying(Reconciliation.IncompleteException.class, e -> {
            assertThat(e.reason()).isEqualTo("UNEXPECTED_ERROR");
            assertThat(e.getCause()).isSameAs(failure);
        });

        verify(alerts).recordSafely(AdminAlertType.RECONCILIATION_FAILED, "UNEXPECTED_ERROR");
        assertThat(output.getAll()).contains("reason=UNEXPECTED_ERROR").doesNotContain("db down");
    }
}
