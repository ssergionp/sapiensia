package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-020, FR-025, SEC-028: the REFUNDED state, the cancellation of an Access by Order, and the active-only unique. */
@Import(TestcontainersConfiguration.class)
// Same context as PaidOrdersSchemaTest (same annotations and properties), so the cached one is reused.
@SpringBootTest(properties = "test.isolated-context=paid-orders-schema")
class RefundSchemaTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    @Autowired
    private Orders orders;

    @Autowired
    private Accesses accesses;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID insertOrder(UUID customerId, UUID productId) {
        UUID id = UUID.randomUUID();
        orders.insert(
                id,
                customerId,
                productId,
                1000,
                "pay_" + UUID.randomUUID().toString().replace("-", ""),
                NOW,
                NOW.plusSeconds(1800));
        return id;
    }

    /** An expired Order: only one PENDING Order per Customer and Product can exist at a time. */
    private UUID insertExpiredOrder(UUID customerId, UUID productId) {
        UUID id = insertOrder(customerId, productId);
        orders.markExpired(id, NOW);
        return id;
    }

    private boolean cancelled(UUID orderId) {
        return jdbc.queryForObject(
                "select cancelled_at is not null from accesses where order_id = ?", Boolean.class, orderId);
    }

    @Test
    @DisplayName("FR-020: o banco aceita REFUNDED só com refunded_at")
    void shouldAcceptRefundedOnlyWithRefundedAt() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        UUID withDate = insertExpiredOrder(customer, product);
        UUID withoutDate = insertOrder(customer, product);

        assertThatThrownBy(() -> jdbc.update("update orders set status = 'REFUNDED' where id = ?", withoutDate))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update orders set refunded_at = now() where id = ?", withoutDate))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(orders.markRefunded(withDate, NOW)).isTrue();
        assertThat(orders.findById(withDate).orElseThrow().status()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(jdbc.queryForObject(
                        "select refunded_at is not null from orders where id = ?", Boolean.class, withDate))
                .isTrue();
    }

    @Test
    @DisplayName("FR-020: PAID sem paid_at e PENDING com paid_at são recusados")
    void shouldStillRefusePaidWithoutPaidAtAndPendingWithPaidAt() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        UUID id = insertOrder(customer, product);

        assertThatThrownBy(() -> jdbc.update("update orders set status = 'PAID' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update orders set paid_at = now() where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("FR-020: markRefunded parte só de PENDING, EXPIRED ou PAID, e uma vez")
    void shouldRefundOnlyFromPendingExpiredOrPaidAndOnlyOnce() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        UUID expired = insertExpiredOrder(customer, product);
        UUID paid = insertExpiredOrder(customer, product);
        orders.markPaid(paid, NOW);
        UUID pending = insertOrder(customer, product);

        assertThat(orders.markRefunded(pending, NOW)).isTrue();
        assertThat(orders.markRefunded(expired, NOW)).isTrue();
        assertThat(orders.markRefunded(paid, NOW)).isTrue();
        assertThat(orders.markRefunded(paid, NOW)).isFalse();
        assertThat(orders.markRefunded(UUID.randomUUID(), NOW)).isFalse();
        assertThat(orders.markPaid(pending, NOW)).isFalse();
    }

    @Test
    @DisplayName(
            "FR-025: dois Acessos ativos do mesmo Cliente e Produto são recusados; um cancelado e um ativo são aceitos")
    void shouldRefuseTwoActiveAccessesButAcceptACancelledAndAnActiveOne() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        UUID first = insertExpiredOrder(customer, product);
        UUID second = insertExpiredOrder(customer, product);
        UUID third = insertExpiredOrder(customer, product);
        String insert = "insert into accesses (customer_id, product_id, order_id, granted_at) values (?, ?, ?, now())";
        jdbc.update(insert, customer, product, first);

        assertThatThrownBy(() -> jdbc.update(insert, customer, product, second))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(accesses.cancelByOrder(first, NOW)).isTrue();
        jdbc.update(insert, customer, product, second);
        assertThatThrownBy(() -> jdbc.update(insert, customer, product, third))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject(
                        "select count(*) from accesses where customer_id = ? and product_id = ?",
                        Integer.class,
                        customer,
                        product))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("SEC-028: cancelByOrder não apaga a linha e não toca o Acesso de outro Pedido")
    void shouldCancelOnlyTheAccessOfThatOrderAndKeepTheRow() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        UUID other = OrderTestSupport.insertPublishedProduct(jdbc, "Q", 1000);
        UUID duplicate = insertExpiredOrder(customer, product); // granted nothing: the Access is the other Order's
        UUID owner = insertExpiredOrder(customer, product);
        UUID otherProductOrder = insertExpiredOrder(customer, other);
        accesses.grant(customer, product, owner, NOW);
        accesses.grant(customer, other, otherProductOrder, NOW);

        assertThat(accesses.cancelByOrder(duplicate, NOW)).isFalse();
        assertThat(cancelled(owner)).isFalse();

        assertThat(accesses.cancelByOrder(owner, NOW)).isTrue();
        assertThat(accesses.cancelByOrder(owner, NOW)).isFalse();

        assertThat(cancelled(owner)).isTrue();
        assertThat(cancelled(otherProductOrder)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from accesses where customer_id = ?", Integer.class, customer))
                .isEqualTo(2);
        assertThat(accesses.exists(customer, product)).isFalse();
        assertThat(accesses.exists(customer, other)).isTrue();
    }
}
