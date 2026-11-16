package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.alert.AdminAlertType;
import br.com.sergio.sapiensia.alert.AdminAlerts;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The nightly comparison of the Asaas Pix charges with the Orders (FR-022, ADR-0002). It never pays or refunds an
 * Order itself: for a difference that the existing processing can settle it stores a marked payment event and
 * schedules the same task the webhook uses; for the rest it records a divergence and an alert. The work is apart
 * from the scheduler so a test can call {@link #run()}.
 */
@Component
class Reconciliation {

    record Result(int charges, int paymentFixes, int refundFixes, int divergences, int ignored) {}

    /** The run stopped before the end; the reason is a closed code (ASAAS_UNAVAILABLE or TOO_MANY_CHARGES). */
    static class IncompleteException extends RuntimeException {

        private final String reason;

        IncompleteException(String reason) {
            super("Reconciliation incomplete: " + reason);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }

    static final String UNAVAILABLE = "ASAAS_UNAVAILABLE";
    static final String TOO_MANY = "TOO_MANY_CHARGES";

    private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final int PAGE_SIZE = 100;
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final PixGateway gateway;
    private final Orders orders;
    private final PaymentEvents events;
    private final PaymentEventResults results;
    private final ReconciliationDivergences divergences;
    private final AdminAlerts alerts;
    private final Clock clock;
    private final SchedulerClient scheduler;
    private final TransactionTemplate transaction;
    private final int windowDays;
    private final int maxPages;

    Reconciliation(
            PixGateway gateway,
            Orders orders,
            PaymentEvents events,
            PaymentEventResults results,
            ReconciliationDivergences divergences,
            AdminAlerts alerts,
            Clock clock,
            @Lazy SchedulerClient scheduler,
            PlatformTransactionManager transactionManager,
            @Value("${app.reconciliation.window-days}") int windowDays,
            @Value("${app.reconciliation.max-pages}") int maxPages) {
        this.gateway = gateway;
        this.orders = orders;
        this.events = events;
        this.results = results;
        this.divergences = divergences;
        this.alerts = alerts;
        this.clock = clock;
        this.scheduler = scheduler;
        this.transaction = new TransactionTemplate(transactionManager);
        this.windowDays = windowDays;
        this.maxPages = maxPages;
    }

    /** One run: counters and the set of charges seen live only in this object, so runs never share state. */
    Result run() {
        return new Run().execute();
    }

    private final class Run {

        private final Set<String> seen = new HashSet<>();
        private int charges;
        private int paymentFixes;
        private int refundFixes;
        private int newDivergences;
        private int ignored;
        // 0-based page of the last listing request, for the log
        private int page;

        Result execute() {
            LocalDate from = LocalDate.now(clock.withZone(SAO_PAULO)).minusDays(windowDays);
            for (page = 0; ; page++) {
                if (page >= maxPages) {
                    // the previous page said there is more
                    fail(TOO_MANY);
                }
                PixChargePage listed = call(() -> gateway.listCharges(from, page * PAGE_SIZE, PAGE_SIZE));
                for (PixCharge charge : listed.charges()) {
                    handleListed(charge);
                }
                if (!listed.hasMore()) {
                    break;
                }
            }
            Instant since = from.atStartOfDay(SAO_PAULO).toInstant();
            for (Orders.Row order : orders.findPaidCreatedSince(since)) {
                String chargeId = order.providerPaymentId();
                if (chargeId == null
                        || !PaymentConfirmation.CHARGE_ID.matcher(chargeId).matches()) {
                    continue;
                }
                if (seen.add(chargeId)) {
                    checkPaidOrder(order, read(chargeId));
                }
            }
            log.info(
                    "Reconciliation finished: charges={} paymentFixes={} refundFixes={} divergences={} ignored={}",
                    charges,
                    paymentFixes,
                    refundFixes,
                    newDivergences,
                    ignored);
            return new Result(charges, paymentFixes, refundFixes, newDivergences, ignored);
        }

        private void handleListed(PixCharge charge) {
            String id = charge.id();
            if (id == null || !PaymentConfirmation.CHARGE_ID.matcher(id).matches()) {
                ignored++;
                return;
            }
            if (!seen.add(id)) {
                return;
            }
            charges++;
            Optional<Orders.Row> found = orders.findByProviderPaymentId(id);
            if (found.isEmpty()) {
                if (charge.isPaid() && isUuid(charge.externalReference())) {
                    diverge(ReconciliationDivergence.CHARGE_WITHOUT_ORDER, id, null);
                } else {
                    // not created by this system, or not paid: only counted (D4)
                    ignored++;
                }
                return;
            }
            Orders.Row order = found.get();
            OrderStatus status = order.status();
            if (charge.isRefunded()) {
                if (status != OrderStatus.REFUNDED) {
                    fix("PAYMENT_REFUNDED", id);
                }
            } else if (charge.isPaid()) {
                if (status == OrderStatus.PENDING || status == OrderStatus.EXPIRED) {
                    fix("PAYMENT_RECEIVED", id);
                } else if (status == OrderStatus.REFUNDED) {
                    // the listing may be late: only the charge read by id confirms the divergence
                    Optional<PixCharge> current = read(id);
                    if (current.isPresent() && current.get().isPaid()) {
                        diverge(ReconciliationDivergence.ORDER_REFUNDED_CHARGE_PAID, id, order.id());
                    }
                }
            } else if (status == OrderStatus.PAID) {
                checkPaidOrder(order, read(id));
            } else if (charge.isRefundInProgress()) {
                ignored++;
            }
        }

        /** The decision for a PAID Order given the charge as read by id (empty: the provider answered 404). */
        private void checkPaidOrder(Orders.Row order, Optional<PixCharge> current) {
            String id = order.providerPaymentId();
            if (current.isEmpty()) {
                diverge(ReconciliationDivergence.ORDER_PAID_CHARGE_NOT_FOUND, id, order.id());
                return;
            }
            PixCharge charge = current.get();
            if (charge.isRefunded()) {
                fix("PAYMENT_REFUNDED", id);
            } else if (!charge.isPaid() && !charge.isRefundInProgress()) {
                diverge(ReconciliationDivergence.ORDER_PAID_CHARGE_NOT_PAID, id, order.id());
            }
        }

        /** The charge as the provider has it now; empty for a 404; any other failure stops the run. */
        private Optional<PixCharge> read(String chargeId) {
            try {
                return Optional.of(gateway.readCharge(chargeId));
            } catch (PixGatewayException e) {
                if (e.kind() == PixGatewayException.Kind.REFUSED) {
                    return Optional.empty();
                }
                fail(UNAVAILABLE);
                return Optional.empty();
            }
        }

        private <T> T call(java.util.function.Supplier<T> listing) {
            try {
                return listing.get();
            } catch (PixGatewayException e) {
                fail(UNAVAILABLE);
                return null;
            }
        }

        private void fail(String reason) {
            alerts.recordSafely(AdminAlertType.RECONCILIATION_FAILED, reason);
            log.error("Reconciliation incomplete: reason={} page={}", reason, page);
            throw new IncompleteException(reason);
        }

        /** Stores the marked event and schedules its processing, exactly as the webhook does. */
        private void fix(String eventType, String chargeId) {
            String providerEventId = "reconciliation:" + eventType + ":" + chargeId;
            // the charge id matches [A-Za-z0-9_]{1,64}, so it needs no escaping
            String payload = "{\"source\":\"RECONCILIATION\",\"payment\":{\"id\":\"" + chargeId + "\"}}";
            boolean scheduled = Boolean.TRUE.equals(transaction.execute(status -> {
                Instant now = clock.instant();
                Optional<UUID> stored =
                        events.insertIfAbsent(providerEventId, eventType, payload, OffsetDateTime.now(clock));
                if (stored.isPresent()) {
                    scheduler.schedule(
                            new TaskInstance<Void>(
                                    PaymentEventTasks.PROCESS, stored.get().toString()),
                            now);
                    return true;
                }
                UUID existing = events.findIdByProviderEventId(providerEventId).orElseThrow();
                if (results.findFinal(existing).isPresent()) {
                    return false;
                }
                return scheduler.scheduleIfNotExists(
                        new TaskInstance<Void>(PaymentEventTasks.PROCESS, existing.toString()), now);
            }));
            if (scheduled) {
                if (eventType.equals("PAYMENT_REFUNDED")) {
                    refundFixes++;
                } else {
                    paymentFixes++;
                }
            }
        }

        /** The row and its alert in one transaction; only a new row alerts and logs. */
        private void diverge(ReconciliationDivergence kind, String chargeId, UUID orderId) {
            boolean created = Boolean.TRUE.equals(transaction.execute(status -> {
                if (!divergences.insertIfAbsent(kind, chargeId, orderId, clock.instant())) {
                    return false;
                }
                alerts.record(AdminAlertType.RECONCILIATION_DIVERGENCE, kind.name(), orderId, null);
                return true;
            }));
            if (created) {
                newDivergences++;
                log.warn("Reconciliation divergence: kind={} chargeId={} orderId={}", kind, chargeId, orderId);
            }
        }
    }

    private static boolean isUuid(String text) {
        return text != null && UUID_TEXT.matcher(text).matches();
    }
}
