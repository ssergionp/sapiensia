package br.com.sergio.sapiensia.payment;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The divergences already found, one per kind and charge (FR-022). It joins the caller's transaction. */
@Repository
class ReconciliationDivergences {

    private final JdbcClient jdbc;

    ReconciliationDivergences(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True only for the row that was inserted now; a divergence already known returns false. */
    boolean insertIfAbsent(ReconciliationDivergence kind, String chargeId, UUID orderId, Instant now) {
        return jdbc.sql("insert into reconciliation_divergences (kind, charge_id, order_id, found_at)"
                                + " values (:kind, :chargeId, :orderId, :now)"
                                + " on conflict (kind, charge_id) do nothing")
                        .param("kind", kind.name())
                        .param("chargeId", chargeId)
                        .param("orderId", orderId, java.sql.Types.OTHER)
                        .param("now", java.sql.Timestamp.from(now))
                        .update()
                == 1;
    }
}
