package br.com.sergio.sapiensia.alert;

import br.com.sergio.sapiensia.admin.AdministratorContact;
import br.com.sergio.sapiensia.email.EmailMessage;
import br.com.sergio.sapiensia.email.EmailSender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One dispatch of the pending alerts (NFR-009): one e-mail per type, in the priority order of {@link AdminAlertType},
 * at most one per type in the group window and at most N per hour. A send failure repeats after the retry delay and
 * gives up after a day. It never records an alert: an alert about an alert could loop (ADR-0005).
 */
@Component
class AdminAlertDispatch {

    /** Counted in e-mails (types), not rows. */
    record Result(int sent, int failed, int gaveUp) {}

    private static final Logger log = LoggerFactory.getLogger(AdminAlertDispatch.class);
    private static final int MAX_LISTED = 20;

    /** The recipient is missing; the claim is rolled back and the rows stay pending. */
    private static final class NoAdministratorException extends RuntimeException {
        NoAdministratorException() {
            super("No Administrator", null, false, false);
        }
    }

    private final AdminAlertStore store;
    private final AdministratorContact contact;
    private final EmailSender sender;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration groupWindow;
    private final int maxEmailsPerHour;
    private final Duration giveUpAfter;
    private final Duration retryDelay;

    AdminAlertDispatch(
            AdminAlertStore store,
            AdministratorContact contact,
            EmailSender sender,
            TransactionTemplate transaction,
            Clock clock,
            @Value("${app.alerts.group-window}") Duration groupWindow,
            @Value("${app.alerts.max-emails-per-hour}") int maxEmailsPerHour,
            @Value("${app.alerts.give-up-after}") Duration giveUpAfter,
            @Value("${app.email.retry-delay}") Duration retryDelay) {
        this.store = store;
        this.contact = contact;
        this.sender = sender;
        this.transaction = transaction;
        this.clock = clock;
        this.groupWindow = groupWindow;
        this.maxEmailsPerHour = maxEmailsPerHour;
        this.giveUpAfter = giveUpAfter;
        this.retryDelay = retryDelay;
    }

    Result run() {
        Instant now = clock.instant();
        int sent = 0;
        int failed = 0;
        int gaveUp = 0;
        int sentLastHour = store.countEmailsSentSince(now.minus(Duration.ofHours(1)));
        for (AdminAlertType type : AdminAlertType.values()) {
            int givenUp = store.giveUp(type, now.minus(giveUpAfter), now);
            if (givenUp > 0) {
                gaveUp++;
                log.error("Admin alert e-mail gave up: type={} occurrences={}", type, givenUp);
            }
            if (sentLastHour >= maxEmailsPerHour
                    || store.sentSince(type, now.minus(groupWindow))
                    || store.attemptedSince(type, now.minus(retryDelay))) {
                continue;
            }
            Boolean outcome = dispatch(type, now);
            if (outcome == null) {
                continue;
            }
            if (outcome) {
                sent++;
                sentLastHour++;
            } else {
                failed++;
            }
        }
        return new Result(sent, failed, gaveUp);
    }

    /** True sent, false failed, null when the type had nothing pending. */
    private Boolean dispatch(AdminAlertType type, Instant now) {
        try {
            return transaction.execute(status -> {
                List<AdminAlertStore.Pending> claimed = store.claim(type, now);
                if (claimed.isEmpty()) {
                    return null;
                }
                String to = contact.findEmail().orElseThrow(NoAdministratorException::new);
                int total = claimed.stream()
                        .mapToInt(AdminAlertStore.Pending::occurrences)
                        .sum();
                List<AdminAlertEmail.Line> listed = claimed.stream()
                        .limit(MAX_LISTED)
                        .map(p -> new AdminAlertEmail.Line(
                                p.occurredAt(), p.detail(), p.paymentEventId(), p.orderId(), p.occurrences()))
                        .toList();
                // a failure here rolls the claim back: the rows stay pending for the retry
                sender.send(new EmailMessage(
                        to,
                        AdminAlertEmail.subject(type, total),
                        AdminAlertEmail.body(type, total, listed, claimed.size() - listed.size(), groupWindow),
                        "admin-alert/" + type + "/" + now.toEpochMilli()));
                log.info("Admin alert e-mail sent: type={} occurrences={}", type, total);
                return Boolean.TRUE;
            });
        } catch (RuntimeException e) {
            String reason = e instanceof NoAdministratorException
                    ? "NO_ADMINISTRATOR"
                    : e.getClass().getSimpleName();
            log.warn("Admin alert e-mail failed: type={} reason={}", type, reason);
            store.markAttempted(type, now);
            return Boolean.FALSE;
        }
    }
}
