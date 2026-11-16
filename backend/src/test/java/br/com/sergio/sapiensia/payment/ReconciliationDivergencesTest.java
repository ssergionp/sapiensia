package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-022: each divergence of the nightly reconciliation is remembered once per kind and charge. */
@Import(TestcontainersConfiguration.class)
// Same context as PaidOrdersSchemaTest (same annotations and properties), so the cached one is reused.
@SpringBootTest(properties = "test.isolated-context=paid-orders-schema")
class ReconciliationDivergencesTest {

    private static final Instant NOW = Instant.parse("2026-11-15T06:30:00Z");

    @Autowired
    private ReconciliationDivergences divergences;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("FR-022: a mesma divergência da mesma cobrança é gravada uma vez")
    void shouldStoreTheSameDivergenceOfTheSameChargeOnce() {
        String chargeId = "pay_" + UUID.randomUUID().toString().replace("-", "");
        UUID orderId = UUID.randomUUID();

        assertThat(divergences.insertIfAbsent(
                        ReconciliationDivergence.ORDER_PAID_CHARGE_NOT_PAID, chargeId, orderId, NOW))
                .isTrue();
        assertThat(divergences.insertIfAbsent(
                        ReconciliationDivergence.ORDER_PAID_CHARGE_NOT_PAID, chargeId, orderId, NOW.plusSeconds(86400)))
                .isFalse();
        // another kind for the same charge is a different divergence, and it may have no Order
        assertThat(divergences.insertIfAbsent(ReconciliationDivergence.CHARGE_WITHOUT_ORDER, chargeId, null, NOW))
                .isTrue();

        assertThat(jdbc.queryForObject(
                        "select count(*) from reconciliation_divergences where charge_id = ?", Integer.class, chargeId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select found_at from reconciliation_divergences where charge_id = ? and kind = ?",
                        java.sql.Timestamp.class,
                        chargeId,
                        "ORDER_PAID_CHARGE_NOT_PAID"))
                .isEqualTo(java.sql.Timestamp.from(NOW));
    }
}
