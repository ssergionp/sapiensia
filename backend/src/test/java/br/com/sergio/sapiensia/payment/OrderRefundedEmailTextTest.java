package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.sergio.sapiensia.alert.AdminAlerts;
import br.com.sergio.sapiensia.customer.CustomerService;
import br.com.sergio.sapiensia.email.EmailSender;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;

/** FR-023: the text of the refund e-mail, without Spring. */
@ExtendWith(OutputCaptureExtension.class)
class OrderRefundedEmailTextTest {

    @Test
    @DisplayName("FR-023: shouldBuildTheApprovedTextEvenWhenTheTitleHasAPercentSign")
    void shouldBuildTheApprovedTextEvenWhenTheTitleHasAPercentSign() {
        String expected = """
                Olá!

                O valor do seu Pedido na SapiensIA foi devolvido.

                Produto: 100% Prático
                Valor: R$ 1.234,56

                Com o Reembolso, o seu Acesso a este Produto foi cancelado e ele não aparece mais na sua Biblioteca.

                Se você não reconhece este Reembolso, escreva para sapiensia.operacao@gmail.com.""";

        assertThat(OrderRefundedEmail.body("100% Prático", 123456)).isEqualTo(expected);
        assertThat(OrderRefundedEmail.SUBJECT).isEqualTo("Seu Pedido na SapiensIA foi reembolsado");
    }

    @Test
    @DisplayName("FR-023: shouldEndTheTaskWithoutRetryWhenTheCustomerIsGone")
    void shouldEndTheTaskWithoutRetryWhenTheCustomerIsGone(CapturedOutput output) {
        Orders orders = mock(Orders.class);
        CustomerService customers = mock(CustomerService.class);
        EmailSender sender = mock(EmailSender.class);
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(orders.refundEmailPending(orderId)).thenReturn(true);
        when(orders.findById(orderId))
                .thenReturn(Optional.of(new Orders.Row(
                        orderId,
                        customerId,
                        UUID.randomUUID(),
                        "Guia",
                        2990,
                        OrderStatus.REFUNDED,
                        "pay_x",
                        Instant.now(),
                        Instant.now(),
                        Instant.now())));
        when(customers.findEmail(customerId)).thenReturn(Optional.empty());
        OrderRefundedEmail email = new OrderRefundedEmail(
                orders, customers, sender, mock(AdminAlerts.class), mock(PlatformTransactionManager.class));

        assertThatCode(() -> email.send(orderId)).doesNotThrowAnyException();

        verify(sender, never()).send(any());
        assertThat(output.getAll())
                .contains("Order refunded e-mail skipped: orderId=" + orderId + " reason=NO_CUSTOMER");
    }
}
