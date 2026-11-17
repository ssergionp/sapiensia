package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.alert.AdminAlertType;
import br.com.sergio.sapiensia.alert.AdminAlerts;
import br.com.sergio.sapiensia.customer.CustomerService;
import br.com.sergio.sapiensia.email.EmailMessage;
import br.com.sergio.sapiensia.email.EmailSender;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The refund e-mail to the Customer (FR-023). It runs on the scheduler thread after the refund committed and is
 * deliberately not transactional: it never touches the Order status nor the Access, only the two refund e-mail
 * columns. A delivery failure propagates so db-scheduler retries; the log carries the Order id and a reason, nothing
 * else.
 */
@Service
class OrderRefundedEmail {

    static final String SUBJECT = "Seu Pedido na SapiensIA foi reembolsado";

    /** Same address as SUPPORT_EMAIL in frontend/src/features/legal/legalTexts.ts and docs/runbooks/suporte-cliente.md. */
    static final String SUPPORT_EMAIL = "suporte@sapiensia.app.br";

    private static final Logger log = LoggerFactory.getLogger(OrderRefundedEmail.class);

    private static final String BODY = """
            Olá!

            O valor do seu Pedido na SapiensIA foi devolvido.

            Produto: %s
            Valor: %s

            Com o Reembolso, o seu Acesso a este Produto foi cancelado e ele não aparece mais na sua Biblioteca.

            Se você não reconhece este Reembolso, escreva para %s.""";

    private final Orders orders;
    private final CustomerService customers;
    private final EmailSender sender;
    private final AdminAlerts alerts;
    private final TransactionTemplate transaction;

    OrderRefundedEmail(
            Orders orders,
            @Lazy CustomerService customers,
            EmailSender sender,
            AdminAlerts alerts,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.customers = customers;
        this.sender = sender;
        this.alerts = alerts;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** The title goes in as an argument, so a "%" in it cannot break the formatting. */
    static String body(String title, int priceCents) {
        return BODY.formatted(title, OrderPaidEmail.formatPrice(priceCents), SUPPORT_EMAIL);
    }

    void send(UUID orderId) {
        Optional<Orders.Row> order = orders.findById(orderId);
        if (!orders.refundEmailPending(orderId)) {
            boolean refunded =
                    order.map(row -> row.status() == OrderStatus.REFUNDED).orElse(false);
            skipped(orderId, refunded ? "ALREADY_SENT" : "NOT_REFUNDED");
            return;
        }
        Orders.Row row = order.orElseThrow();
        Optional<String> to = customers.findEmail(row.customerId());
        if (to.isEmpty()) {
            skipped(orderId, "NO_CUSTOMER");
            return;
        }
        sender.send(new EmailMessage(
                to.get(), SUBJECT, body(row.productTitle(), row.priceCents()), "order-refunded-email/" + orderId));
        // a resend withdraws the give-up alert, so it only goes out if the e-mail really failed
        boolean marked = Boolean.TRUE.equals(transaction.execute(status -> {
            if (!orders.markRefundEmailSent(orderId, Instant.now())) {
                return false;
            }
            alerts.resolve(AdminAlertType.ORDER_REFUNDED_EMAIL_GAVE_UP, orderId);
            return true;
        }));
        if (marked) {
            log.info("Order refunded e-mail sent: orderId={}", orderId);
        } else {
            skipped(orderId, "ALREADY_SENT");
        }
    }

    void giveUp(UUID orderId) {
        // the failure mark and its alert exist together or not at all
        transaction.executeWithoutResult(status -> {
            if (orders.markRefundEmailFailed(orderId, Instant.now())) {
                alerts.record(AdminAlertType.ORDER_REFUNDED_EMAIL_GAVE_UP, null, orderId, null);
            }
        });
        log.error("Order refunded e-mail gave up: orderId={}", orderId);
    }

    private static void skipped(UUID orderId, String reason) {
        log.info("Order refunded e-mail skipped: orderId={} reason={}", orderId, reason);
    }
}
