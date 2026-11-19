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

/** FR-024: the text of the payment confirmation e-mail, without Spring. */
@ExtendWith(OutputCaptureExtension.class)
class OrderPaidEmailTextTest {

    @Test
    @DisplayName("FR-024: shouldFormatThePriceInReaisWithARegularSpace")
    void shouldFormatThePriceInReaisWithARegularSpace() {
        assertThat(OrderPaidEmail.formatPrice(2990)).isEqualTo("R$ 29,90");
        assertThat(OrderPaidEmail.formatPrice(123456)).isEqualTo("R$ 1.234,56");
    }

    @Test
    @DisplayName("FR-024: shouldBuildTheApprovedTextEvenWhenTheTitleHasAPercentSign")
    void shouldBuildTheApprovedTextEvenWhenTheTitleHasAPercentSign() {
        String expected = """
                Olá!

                Recebemos o seu Pix. O pagamento do seu Pedido na SapiensIA foi confirmado.

                Produto: 100% Prático
                Valor: R$ 29,90

                Você já tem Acesso a este Produto. Para baixar, entre na sua conta e abra a sua Biblioteca:

                http://localhost:5173/biblioteca

                Obrigado pela compra!""";

        assertThat(OrderPaidEmail.body("100% Prático", 2990, "http://localhost:5173"))
                .isEqualTo(expected);
        assertThat(OrderPaidEmail.SUBJECT).isEqualTo("Seu pagamento na SapiensIA foi confirmado");
    }

    @Test
    @DisplayName("FR-024: shouldEndTheTaskWithoutRetryWhenTheCustomerIsGone")
    void shouldEndTheTaskWithoutRetryWhenTheCustomerIsGone(CapturedOutput output) {
        Orders orders = mock(Orders.class);
        CustomerService customers = mock(CustomerService.class);
        EmailSender sender = mock(EmailSender.class);
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(orders.paymentEmailPending(orderId)).thenReturn(true);
        when(orders.findById(orderId))
                .thenReturn(Optional.of(new Orders.Row(
                        orderId,
                        customerId,
                        UUID.randomUUID(),
                        "Guia",
                        2990,
                        OrderStatus.PAID,
                        "pay_x",
                        Instant.now(),
                        Instant.now(),
                        Instant.now())));
        when(customers.findEmail(customerId)).thenReturn(Optional.empty());
        OrderPaidEmail email = new OrderPaidEmail(
                orders,
                customers,
                sender,
                "http://localhost:5173",
                mock(AdminAlerts.class),
                mock(PlatformTransactionManager.class));

        assertThatCode(() -> email.send(orderId)).doesNotThrowAnyException();

        verify(sender, never()).send(any());
        assertThat(output.getAll()).contains("reason=NO_CUSTOMER").contains(orderId.toString());
    }
}
