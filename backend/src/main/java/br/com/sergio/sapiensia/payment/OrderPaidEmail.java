package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.alert.AdminAlertType;
import br.com.sergio.sapiensia.alert.AdminAlerts;
import br.com.sergio.sapiensia.customer.CustomerService;
import br.com.sergio.sapiensia.email.EmailMessage;
import br.com.sergio.sapiensia.email.EmailSender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The payment confirmation e-mail (FR-024). It runs on the scheduler thread after the payment committed and is
 * deliberately not transactional: it never touches the Order status nor the Access, only the two e-mail columns. A
 * delivery failure propagates so db-scheduler retries; the log carries the Order id and a reason, nothing else.
 */
@Service
class OrderPaidEmail {

    static final String SUBJECT = "Seu pagamento na SapiensIA foi confirmado";

    private static final Logger log = LoggerFactory.getLogger(OrderPaidEmail.class);

    private static final String BODY = """
            Olá!

            Recebemos o seu Pix. O pagamento do seu Pedido na SapiensIA foi confirmado.

            Produto: %s
            Valor: %s

            Você já tem Acesso a este Produto. Para baixar, entre na sua conta e abra a sua Biblioteca:

            %s

            Obrigado pela compra!""";

    private final Orders orders;
    private final CustomerService customers;
    private final EmailSender sender;
    private final String baseUrl;
    private final AdminAlerts alerts;
    private final TransactionTemplate transaction;

    OrderPaidEmail(
            Orders orders,
            @Lazy CustomerService customers,
            EmailSender sender,
            @Value("${app.public-base-url}") String baseUrl,
            AdminAlerts alerts,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.customers = customers;
        this.sender = sender;
        this.baseUrl = baseUrl;
        this.alerts = alerts;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    static String formatPrice(int priceCents) {
        return String.format(Locale.of("pt", "BR"), "R$ %,.2f", BigDecimal.valueOf(priceCents, 2));
    }

    /** The title goes in as an argument, so a "%" in it cannot break the formatting. */
    static String body(String title, int priceCents, String baseUrl) {
        return BODY.formatted(title, formatPrice(priceCents), baseUrl + "/biblioteca");
    }

    void send(UUID orderId) {
        Optional<Orders.Row> order = orders.findById(orderId);
        if (!orders.paymentEmailPending(orderId)) {
            boolean paid = order.map(row -> row.status() == OrderStatus.PAID).orElse(false);
            skipped(orderId, paid ? "ALREADY_SENT" : "NOT_PAID");
            return;
        }
        Orders.Row row = order.orElseThrow();
        Optional<String> to = customers.findEmail(row.customerId());
        if (to.isEmpty()) {
            skipped(orderId, "NO_CUSTOMER");
            return;
        }
        sender.send(new EmailMessage(
                to.get(), SUBJECT, body(row.productTitle(), row.priceCents(), baseUrl), "order-paid-email/" + orderId));
        // a resend withdraws the give-up alert, so it only goes out if the e-mail really failed
        boolean marked = Boolean.TRUE.equals(transaction.execute(status -> {
            if (!orders.markPaymentEmailSent(orderId, Instant.now())) {
                return false;
            }
            alerts.resolve(AdminAlertType.ORDER_PAID_EMAIL_GAVE_UP, orderId);
            return true;
        }));
        if (marked) {
            log.info("Order paid e-mail sent: orderId={}", orderId);
        } else {
            skipped(orderId, "ALREADY_SENT");
        }
    }

    void giveUp(UUID orderId) {
        // the failure mark and its alert exist together or not at all
        transaction.executeWithoutResult(status -> {
            if (orders.markPaymentEmailFailed(orderId, Instant.now())) {
                alerts.record(AdminAlertType.ORDER_PAID_EMAIL_GAVE_UP, null, orderId, null);
            }
        });
        log.error("Order paid e-mail gave up: orderId={}", orderId);
    }

    private static void skipped(UUID orderId, String reason) {
        log.info("Order paid e-mail skipped: orderId={} reason={}", orderId, reason);
    }
}
