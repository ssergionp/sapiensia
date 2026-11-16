package br.com.sergio.sapiensia.alert;

import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes Administrator alerts (NFR-009). It only writes a row: the e-mail goes out later, from a recurring task, never
 * inside the transaction of whoever detected the problem. The log never carries the {@code detail} (ADR-0006).
 */
@Service
public class AdminAlerts {

    private static final Logger log = LoggerFactory.getLogger(AdminAlerts.class);
    private static final Pattern DETAIL = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final AdminAlertStore store;
    private final Clock clock;

    AdminAlerts(AdminAlertStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Records the alert in the caller's transaction, so it exists exactly when the caller's result does. An identical
     * pending alert only raises its counter. {@code detail} is a closed code, never free text.
     *
     * @throws IllegalArgumentException when {@code detail} is not {@code [A-Za-z0-9_-]{1,64}}
     */
    public void record(AdminAlertType type, String detail, UUID orderId, UUID paymentEventId) {
        if (detail != null && !DETAIL.matcher(detail).matches()) {
            throw new IllegalArgumentException("Alert detail must be a closed code");
        }
        store.upsert(type, detail, orderId, paymentEventId, clock.instant());
    }

    /** For callers without a transaction: never throws; a failure is logged with the exception name only. */
    public void recordSafely(AdminAlertType type, String detail) {
        try {
            record(type, detail, null, null);
        } catch (RuntimeException e) {
            log.error(
                    "Admin alert not recorded: type={} reason={}",
                    type,
                    e.getClass().getSimpleName());
        }
    }

    /** Marks the pending alerts of this type and Order as resolved. */
    public void resolve(AdminAlertType type, UUID orderId) {
        store.resolve(type, orderId, clock.instant());
    }
}
