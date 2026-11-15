package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
// Own context (and database): this class leaves Orders, Accesses and insert-only results behind, and the Customer and
// Product tests of the default context delete whole tables, which those rows would make impossible.
@SpringBootTest(properties = "test.isolated-context=paid-orders-schema")
class PaidOrdersSchemaTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    @Autowired
    private Orders orders;

    @Autowired
    private Accesses accesses;

    @Autowired
    private PaymentEvents events;

    @Autowired
    private PaymentEventResults results;

    @Autowired
    private JdbcTemplate jdbc;

    private static String chargeId() {
        return "pay_" + UUID.randomUUID().toString().replace("-", "");
    }

    private UUID insertOrder(UUID customerId, UUID productId, String chargeId) {
        UUID id = UUID.randomUUID();
        orders.insert(id, customerId, productId, 1000, chargeId, NOW, NOW.plusSeconds(1800));
        return id;
    }

    private UUID insertEvent() {
        return events.insertIfAbsent("evt_" + UUID.randomUUID(), "PAYMENT_RECEIVED", "{}", OffsetDateTime.now())
                .orElseThrow();
    }

    private int countResults(UUID event) {
        return jdbc.queryForObject(
                "select count(*) from payment_event_results where payment_event_id = ?", Integer.class, event);
    }

    @Test
    @DisplayName("FR-024: shouldMarkAPendingOrderAsPaidOnlyOnce")
    void shouldMarkAPendingOrderAsPaidOnlyOnce() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        UUID id = insertOrder(customer, product, chargeId());

        assertThat(orders.markPaid(id, NOW)).isTrue();
        assertThat(orders.markPaid(id, NOW)).isFalse();

        assertThat(orders.findById(id).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
        assertThat(jdbc.queryForObject("select paid_at is not null from orders where id = ?", Boolean.class, id))
                .isTrue();
    }

    @Test
    @DisplayName("ADR-0003: shouldMarkAnExpiredOrderAsPaid")
    void shouldMarkAnExpiredOrderAsPaid() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        UUID id = insertOrder(customer, product, chargeId());
        assertThat(orders.markExpired(id, NOW)).isTrue();

        assertThat(orders.markPaid(id, NOW.plusSeconds(60))).isTrue();

        assertThat(orders.findById(id).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
        assertThat(jdbc.queryForObject("select expired_at is not null from orders where id = ?", Boolean.class, id))
                .isTrue();
    }

    @Test
    @DisplayName("shouldRefuseAPaidOrderWithoutPaidAt")
    void shouldRefuseAPaidOrderWithoutPaidAt() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        UUID id = insertOrder(customer, product, chargeId());

        assertThatThrownBy(() -> jdbc.update("update orders set status = 'PAID' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("shouldRefuseAPendingOrderWithPaidAt")
    void shouldRefuseAPendingOrderWithPaidAt() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        UUID id = insertOrder(customer, product, chargeId());

        assertThatThrownBy(() -> jdbc.update("update orders set paid_at = now() where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("SEC-025: shouldGrantOneAccessPerCustomerAndProduct")
    void shouldGrantOneAccessPerCustomerAndProduct() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        UUID first = insertOrder(customer, product, chargeId());
        orders.markExpired(first, NOW);
        UUID second = insertOrder(customer, product, chargeId());

        assertThat(accesses.exists(customer, product)).isFalse();
        assertThat(accesses.grant(customer, product, first, NOW)).isTrue();
        assertThat(accesses.grant(customer, product, second, NOW)).isFalse();

        assertThat(accesses.exists(customer, product)).isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from accesses where customer_id = ? and product_id = ?",
                        Integer.class,
                        customer,
                        product))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FR-025: o Pedido novo, pago, concede um Acesso novo e a linha cancelada continua")
    void shouldGrantANewAccessAfterACancellationAndKeepTheCancelledRow() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        UUID first = insertOrder(customer, product, chargeId());
        orders.markPaid(first, NOW);
        accesses.grant(customer, product, first, NOW);
        orders.markRefunded(first, NOW);
        accesses.cancelByOrder(first, NOW);
        UUID second = insertOrder(customer, product, chargeId());
        orders.markPaid(second, NOW.plusSeconds(60));

        assertThat(accesses.grant(customer, product, second, NOW.plusSeconds(60)))
                .isTrue();

        assertThat(accesses.exists(customer, product)).isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from accesses where customer_id = ? and product_id = ?",
                        Integer.class,
                        customer,
                        product))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select cancelled_at is not null from accesses where order_id = ?", Boolean.class, first))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select cancelled_at is null from accesses where order_id = ?", Boolean.class, second))
                .isTrue();
    }

    @Test
    @DisplayName("shouldRefuseAnAccessForAnOrderThatAlreadyGrantedOne")
    void shouldRefuseAnAccessForAnOrderThatAlreadyGrantedOne() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        UUID other = OrderTestSupport.insertPublishedProduct(jdbc, "Q", 1000);
        UUID order = insertOrder(customer, product, chargeId());
        accesses.grant(customer, product, order, NOW);

        assertThatThrownBy(() -> jdbc.update(
                        "insert into accesses (customer_id, product_id, order_id, granted_at) values (?, ?, ?, now())",
                        customer,
                        other,
                        order))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("SEC-025: shouldKeepTheFirstFinalResultOfAnEvent")
    void shouldKeepTheFirstFinalResultOfAnEvent() {
        UUID event = insertEvent();

        assertThat(results.record(event, PaymentResult.PAID, null, NOW)).isTrue();
        assertThat(results.record(event, PaymentResult.NOT_PIX, null, NOW)).isFalse();

        assertThat(results.findFinal(event)).contains(PaymentResult.PAID);
        assertThat(countResults(event)).isEqualTo(1);
    }

    @Test
    @DisplayName("shouldAcceptAFinalResultAfterGivingUp")
    void shouldAcceptAFinalResultAfterGivingUp() {
        UUID event = insertEvent();

        assertThat(results.record(event, PaymentResult.GAVE_UP, null, NOW)).isTrue();
        assertThat(results.findFinal(event)).isEmpty();
        assertThat(results.record(event, PaymentResult.PAID, null, NOW)).isTrue();

        assertThat(results.findFinal(event)).contains(PaymentResult.PAID);
        assertThat(countResults(event)).isEqualTo(2);
    }

    @Test
    @DisplayName("shouldNotRecordGivingUpAfterAFinalResult")
    void shouldNotRecordGivingUpAfterAFinalResult() {
        UUID event = insertEvent();
        results.record(event, PaymentResult.PAID, null, NOW);

        assertThat(results.record(event, PaymentResult.GAVE_UP, null, NOW)).isFalse();

        assertThat(countResults(event)).isEqualTo(1);
    }

    @Test
    @DisplayName("shouldRefuseAResultForAnUnknownEvent")
    void shouldRefuseAResultForAnUnknownEvent() {
        assertThatThrownBy(() -> results.record(UUID.randomUUID(), PaymentResult.PAID, null, NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("shouldFindAnOrderByItsChargeId")
    void shouldFindAnOrderByItsChargeId() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        String charge = chargeId();
        UUID id = insertOrder(customer, product, charge);

        assertThat(orders.findByProviderPaymentId(charge).orElseThrow().id()).isEqualTo(id);
    }

    @Test
    @DisplayName("shouldFindNoOrderForAnUnknownCharge")
    void shouldFindNoOrderForAnUnknownCharge() {
        assertThat(orders.findByProviderPaymentId(chargeId())).isEmpty();
    }

    @Test
    @DisplayName("shouldLeaveANewPendingOrderPossibleAfterThePreviousOneWasPaid")
    void shouldLeaveANewPendingOrderPossibleAfterThePreviousOneWasPaid() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "P", 1000);
        UUID first = insertOrder(customer, product, chargeId());
        orders.markPaid(first, NOW);

        UUID second = insertOrder(customer, product, chargeId());

        assertThat(orders.findById(second).orElseThrow().status()).isEqualTo(OrderStatus.PENDING);
    }
}
