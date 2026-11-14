package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.audit.SwitchableAuditLog;
import br.com.sergio.sapiensia.audit.SwitchableAuditLogConfig;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-024, SEC-026: only a payment the provider confirms, in full and for this Order, pays the Order and grants. */
@ExtendWith(OutputCaptureExtension.class)
@Import({
    TestcontainersConfiguration.class,
    SwitchablePixGatewayConfig.class,
    SwitchableAuditLogConfig.class,
    PaymentConfirmationTest.ClockConfig.class
})
@SpringBootTest(
        properties = {
            "test.isolated-context=payment-confirmation",
            // no background task may process or expire anything between the steps of a test
            "db-scheduler.polling-interval=PT1H"
        })
class PaymentConfirmationTest {

    private static final int PRICE = 2990;

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        void advance(Duration duration) {
            now.updateAndGet(t -> t.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock paymentConfirmationClock() {
            return new MutableClock();
        }
    }

    /** One Customer, one Product, one pending Order with its charge, and one stored event for that charge. */
    private record Fixture(UUID customerId, UUID productId, UUID orderId, String chargeId, UUID eventId, String evt) {}

    @Autowired
    PaymentConfirmation confirmation;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Orders orders;

    @Autowired
    PaymentEvents events;

    @Autowired
    PaymentEventResults results;

    @Autowired
    SwitchablePixGateway gateway;

    @Autowired
    SwitchableAuditLog audit;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void setUp() {
        gateway.reset();
        audit.failWrites(false);
    }

    @AfterEach
    void tearDown() {
        gateway.reset();
        audit.failWrites(false);
    }

    private Fixture fixture() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia de Contratos", PRICE);
        return fixtureFor(customer, product);
    }

    private Fixture fixtureFor(UUID customer, UUID product) {
        String chargeId = "pay_" + UUID.randomUUID().toString().replace("-", "");
        UUID orderId = UUID.randomUUID();
        Instant now = clock.instant();
        orders.insert(orderId, customer, product, PRICE, chargeId, now, now.plus(Duration.ofMinutes(30)));
        String json = "{\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"" + chargeId + "\"}}";
        return eventFor(customer, product, orderId, chargeId, json);
    }

    private Fixture eventFor(UUID customer, UUID product, UUID orderId, String chargeId, String payloadJson) {
        String evt = "evt_" + UUID.randomUUID().toString().replace("-", "");
        UUID eventId = events.insertIfAbsent(evt, "PAYMENT_RECEIVED", payloadJson, OffsetDateTime.now())
                .orElseThrow();
        return new Fixture(customer, product, orderId, chargeId, eventId, evt);
    }

    private Fixture secondEventOf(Fixture f) {
        String json = "{\"event\":\"PAYMENT_CONFIRMED\",\"payment\":{\"id\":\"" + f.chargeId() + "\"}}";
        return eventFor(f.customerId(), f.productId(), f.orderId(), f.chargeId(), json);
    }

    private PixCharge charge(Fixture f, String status, String value, String billingType, String reference) {
        return new PixCharge(f.chargeId(), status, new BigDecimal(value), billingType, reference, false);
    }

    private PixCharge paid(Fixture f) {
        return charge(f, "RECEIVED", "29.90", "PIX", f.orderId().toString());
    }

    private void provider(Fixture f, PixCharge charge) {
        gateway.answerCharge(f.chargeId(), charge);
    }

    private String statusOf(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private long accesses(Fixture f) {
        return jdbc.queryForObject(
                "select count(*) from accesses where customer_id = ? and product_id = ?",
                Long.class,
                f.customerId(),
                f.productId());
    }

    private long grantedAudits(UUID orderId) {
        return jdbc.queryForObject(
                "select count(*) from audit_events where action = 'ACCESS_GRANTED' and object_id = ?",
                Long.class,
                orderId);
    }

    private String storedResult(UUID eventId) {
        var all = jdbc.queryForList(
                "select result from payment_event_results where payment_event_id = ? order by processed_at",
                String.class,
                eventId);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private long storedResults(UUID eventId) {
        return jdbc.queryForObject(
                "select count(*) from payment_event_results where payment_event_id = ?", Long.class, eventId);
    }

    private void assertNothingGranted(Fixture f) {
        assertThat(statusOf(f.orderId())).isEqualTo("PENDING");
        assertThat(accesses(f)).isZero();
        assertThat(grantedAudits(f.orderId())).isZero();
    }

    private void assertRefused(Fixture f, PaymentResult expected, CapturedOutput output) {
        assertThat(confirmation.process(f.eventId())).isEqualTo(expected);
        assertThat(storedResult(f.eventId())).isEqualTo(expected.name());
        assertNothingGranted(f);
        assertThat(output.getAll()).contains("Payment event needs review").contains(f.evt());
    }

    @Test
    @DisplayName("FR-024, SEC-026: shouldPayThePendingOrderAndGrantTheAccessWhenTheProviderConfirms")
    void shouldPayThePendingOrderAndGrantTheAccessWhenTheProviderConfirms() {
        Fixture f = fixture();
        provider(f, paid(f));

        assertThat(confirmation.process(f.eventId())).isEqualTo(PaymentResult.PAID);

        assertThat(statusOf(f.orderId())).isEqualTo("PAID");
        assertThat(jdbc.queryForObject(
                        "select paid_at is not null from orders where id = ?", Boolean.class, f.orderId()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select order_id from accesses where customer_id = ? and product_id = ?",
                        UUID.class,
                        f.customerId(),
                        f.productId()))
                .isEqualTo(f.orderId());
        var audits = jdbc.queryForList(
                "select actor_id, object_type, result from audit_events"
                        + " where action = 'ACCESS_GRANTED' and object_id = ?",
                f.orderId());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("actor_id", null).containsEntry("object_type", "ORDER");
        assertThat(audits.get(0).get("result")).isEqualTo("SUCCESS");
        assertThat(storedResult(f.eventId())).isEqualTo("PAID");
    }

    @Test
    @DisplayName("FR-024: shouldAcceptConfirmedAsPaid")
    void shouldAcceptConfirmedAsPaid() {
        Fixture f = fixture();
        provider(f, charge(f, "CONFIRMED", "29.90", "PIX", f.orderId().toString()));

        assertThat(confirmation.process(f.eventId())).isEqualTo(PaymentResult.PAID);
        assertThat(accesses(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-0003, SEC-031: shouldPayAnOrderThatHadAlreadyExpired")
    void shouldPayAnOrderThatHadAlreadyExpired() {
        Fixture f = fixture();
        assertThat(orders.markExpired(f.orderId(), clock.instant())).isTrue();
        provider(f, paid(f));

        assertThat(confirmation.process(f.eventId())).isEqualTo(PaymentResult.PAID);

        assertThat(statusOf(f.orderId())).isEqualTo("PAID");
        assertThat(accesses(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-031: shouldPayAnOrderWhoseEventArrivesDaysLate")
    void shouldPayAnOrderWhoseEventArrivesDaysLate() {
        Fixture f = fixture();
        provider(f, paid(f));
        clock.advance(Duration.ofDays(5));

        assertThat(confirmation.process(f.eventId())).isEqualTo(PaymentResult.PAID);

        assertThat(statusOf(f.orderId())).isEqualTo("PAID");
        assertThat(accesses(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-025: shouldDoNothingWhenTheSameEventIsProcessedAgain")
    void shouldDoNothingWhenTheSameEventIsProcessedAgain() {
        Fixture f = fixture();
        provider(f, paid(f));
        confirmation.process(f.eventId());
        long reads = gateway.count("READ_CHARGE");

        assertThat(confirmation.process(f.eventId())).isEqualTo(PaymentResult.PAID);

        assertThat(accesses(f)).isEqualTo(1);
        assertThat(grantedAudits(f.orderId())).isEqualTo(1);
        assertThat(storedResults(f.eventId())).isEqualTo(1);
        assertThat(gateway.count("READ_CHARGE")).isEqualTo(reads);
    }

    @Test
    @DisplayName("SEC-025: shouldAnswerAlreadyPaidForASecondEventOfTheSamePayment")
    void shouldAnswerAlreadyPaidForASecondEventOfTheSamePayment() {
        Fixture first = fixture();
        provider(first, paid(first));
        confirmation.process(first.eventId());
        Fixture second = secondEventOf(first);
        long reads = gateway.count("READ_CHARGE");

        assertThat(confirmation.process(second.eventId())).isEqualTo(PaymentResult.ALREADY_PAID);

        assertThat(gateway.count("READ_CHARGE")).isEqualTo(reads);
        assertThat(accesses(first)).isEqualTo(1);
        assertThat(grantedAudits(first.orderId())).isEqualTo(1);
        assertThat(storedResult(second.eventId())).isEqualTo("ALREADY_PAID");
    }

    @Test
    @DisplayName("SEC-031: shouldRecordAnOrphanChargeAndIgnoreIt")
    void shouldRecordAnOrphanChargeAndIgnoreIt() {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia", PRICE);
        Fixture orphan = eventFor(
                customer,
                product,
                null,
                "pay_orphan",
                "{\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"pay_orphan_"
                        + UUID.randomUUID().toString().replace("-", "") + "\"}}");

        assertThat(confirmation.process(orphan.eventId())).isEqualTo(PaymentResult.NO_ORDER);

        assertThat(storedResult(orphan.eventId())).isEqualTo("NO_ORDER");
        assertThat(gateway.count("READ_CHARGE")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from accesses where customer_id = ?", Long.class, customer))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"29.89", "29.91", "2990", "0"})
    @DisplayName("SEC-026: shouldNotGrantWhenTheValueDiffers")
    void shouldNotGrantWhenTheValueDiffers(String value, CapturedOutput output) {
        Fixture f = fixture();
        provider(f, charge(f, "RECEIVED", value, "PIX", f.orderId().toString()));

        assertRefused(f, PaymentResult.AMOUNT_MISMATCH, output);
    }

    @Test
    @DisplayName("SEC-026: shouldPayWhenTheValueHasAnotherScale")
    void shouldPayWhenTheValueHasAnotherScale() {
        Fixture f = fixture();
        provider(f, charge(f, "RECEIVED", "29.9", "PIX", f.orderId().toString()));

        assertThat(confirmation.process(f.eventId())).isEqualTo(PaymentResult.PAID);
    }

    @Test
    @DisplayName("SEC-026: shouldNotGrantWhenTheProviderReturnsAnotherChargeId")
    void shouldNotGrantWhenTheProviderReturnsAnotherChargeId(CapturedOutput output) {
        Fixture f = fixture();
        provider(
                f,
                new PixCharge(
                        "pay_other",
                        "RECEIVED",
                        new BigDecimal("29.90"),
                        "PIX",
                        f.orderId().toString(),
                        false));

        assertRefused(f, PaymentResult.CHARGE_ID_MISMATCH, output);
    }

    @Test
    @DisplayName("SEC-026: shouldNotGrantWhenTheExternalReferenceIsAnotherOrder")
    void shouldNotGrantWhenTheExternalReferenceIsAnotherOrder(CapturedOutput output) {
        Fixture f = fixture();
        provider(f, charge(f, "RECEIVED", "29.90", "PIX", UUID.randomUUID().toString()));

        assertRefused(f, PaymentResult.REFERENCE_MISMATCH, output);
    }

    @Test
    @DisplayName("SEC-026: shouldNotGrantWhenTheExternalReferenceIsMissing")
    void shouldNotGrantWhenTheExternalReferenceIsMissing(CapturedOutput output) {
        Fixture f = fixture();
        provider(f, charge(f, "RECEIVED", "29.90", "PIX", null));

        assertRefused(f, PaymentResult.REFERENCE_MISMATCH, output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECEIVED_IN_CASH", "BOLETO", "CREDIT_CARD"})
    @DisplayName("SEC-026: shouldNotGrantWhenTheChargeIsNotPix")
    void shouldNotGrantWhenTheChargeIsNotPix(String billingType, CapturedOutput output) {
        Fixture f = fixture();
        provider(f, charge(f, "RECEIVED", "29.90", billingType, f.orderId().toString()));

        assertRefused(f, PaymentResult.NOT_PIX, output);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "REFUNDED",
                "REFUND_IN_PROGRESS",
                "CHARGEBACK_REQUESTED",
                "RECEIVED_IN_CASH",
                "AWAITING_RISK_ANALYSIS"
            })
    @DisplayName("SEC-026, SEC-031: shouldNotGrantWhenTheStatusIsNotReceivedOrConfirmed")
    void shouldNotGrantWhenTheStatusIsNotReceivedOrConfirmed(String status, CapturedOutput output) {
        Fixture f = fixture();
        provider(f, charge(f, status, "29.90", "PIX", f.orderId().toString()));

        assertRefused(f, PaymentResult.NOT_PAID, output);
    }

    @Test
    @DisplayName("SEC-026: shouldNotGrantWhenTheProviderDoesNotKnowTheCharge")
    void shouldNotGrantWhenTheProviderDoesNotKnowTheCharge(CapturedOutput output) {
        Fixture f = fixture();
        gateway.failAt("READ_CHARGE", PixGatewayException.Kind.REFUSED);

        assertRefused(f, PaymentResult.CHARGE_NOT_FOUND, output);
    }

    @Test
    @DisplayName("SEC-031: shouldRecordNothingAndAskForARetryWhileTheProviderIsUnavailable")
    void shouldRecordNothingAndAskForARetryWhileTheProviderIsUnavailable() {
        Fixture f = fixture();
        provider(f, paid(f));
        gateway.failAt("READ_CHARGE", PixGatewayException.Kind.UNAVAILABLE);

        assertThatThrownBy(() -> confirmation.process(f.eventId()))
                .isInstanceOf(PaymentConfirmation.PaymentNotReadyException.class);

        assertThat(storedResults(f.eventId())).isZero();
        assertNothingGranted(f);

        gateway.failAt("READ_CHARGE", null);
        assertThat(confirmation.process(f.eventId())).isEqualTo(PaymentResult.PAID);
        assertThat(statusOf(f.orderId())).isEqualTo("PAID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "OVERDUE"})
    @DisplayName("SEC-031: shouldAskForARetryWhileTheProviderStillShowsTheChargeAsPending")
    void shouldAskForARetryWhileTheProviderStillShowsTheChargeAsPending(String status) {
        Fixture f = fixture();
        provider(f, charge(f, status, "29.90", "PIX", f.orderId().toString()));

        assertThatThrownBy(() -> confirmation.process(f.eventId()))
                .isInstanceOf(PaymentConfirmation.PaymentNotReadyException.class);

        assertThat(storedResults(f.eventId())).isZero();
        assertNothingGranted(f);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"event\":\"PAYMENT_RECEIVED\"}",
                "{\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":123}}",
                "{\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"\"}}",
                "{\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"pay 1\"}}",
                "{\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"p23456789012345678901234567890123456789012345678901234567890123456\"}}",
                "{\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"'; drop table orders; --\"}}"
            })
    @DisplayName("shouldRecordAnEventWithoutAChargeIdAsInvalid")
    void shouldRecordAnEventWithoutAChargeIdAsInvalid(String payload, CapturedOutput output) {
        UUID customer = OrderTestSupport.insertCustomer(jdbc);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia", PRICE);
        Fixture f = eventFor(customer, product, null, null, payload);

        assertThat(confirmation.process(f.eventId())).isEqualTo(PaymentResult.INVALID_PAYLOAD);

        assertThat(storedResult(f.eventId())).isEqualTo("INVALID_PAYLOAD");
        assertThat(gateway.count("READ_CHARGE")).isZero();
        assertThat(output.getAll()).contains("Payment event needs review").doesNotContain("drop table");
    }

    @Test
    @DisplayName("ADR-0003: shouldGrantWhenThePaidChargeIsMarkedDeleted")
    void shouldGrantWhenThePaidChargeIsMarkedDeleted() {
        Fixture f = fixture();
        provider(
                f,
                new PixCharge(
                        f.chargeId(),
                        "RECEIVED",
                        new BigDecimal("29.90"),
                        "PIX",
                        f.orderId().toString(),
                        true));

        assertThat(confirmation.process(f.eventId())).isEqualTo(PaymentResult.PAID);
        assertThat(accesses(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-024: shouldMarkTheSecondPaidOrderOfTheSameProductAsADuplicatePurchase")
    void shouldMarkTheSecondPaidOrderOfTheSameProductAsADuplicatePurchase(CapturedOutput output) {
        Fixture first = fixture();
        provider(first, paid(first));
        confirmation.process(first.eventId());
        Fixture second = fixtureFor(first.customerId(), first.productId());
        provider(second, paid(second));

        assertThat(confirmation.process(second.eventId())).isEqualTo(PaymentResult.DUPLICATE_PURCHASE);

        assertThat(statusOf(second.orderId())).isEqualTo("PAID");
        assertThat(accesses(first)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select order_id from accesses where customer_id = ? and product_id = ?",
                        UUID.class,
                        first.customerId(),
                        first.productId()))
                .isEqualTo(first.orderId());
        assertThat(grantedAudits(first.orderId())).isEqualTo(1);
        assertThat(grantedAudits(second.orderId())).isZero();
        assertThat(storedResult(second.eventId())).isEqualTo("DUPLICATE_PURCHASE");
        assertThat(output.getAll()).contains("Payment event needs review").contains(second.evt());
    }

    @Test
    @DisplayName("shouldGrantNothingWhenTheAuditTrailCannotBeWritten")
    void shouldGrantNothingWhenTheAuditTrailCannotBeWritten() {
        Fixture f = fixture();
        provider(f, paid(f));
        audit.failWrites(true);

        assertThatThrownBy(() -> confirmation.process(f.eventId())).isInstanceOf(RuntimeException.class);

        assertNothingGranted(f);
        assertThat(storedResults(f.eventId())).isZero();
    }

    @Test
    @DisplayName("shouldRecordThatProcessingGaveUp")
    void shouldRecordThatProcessingGaveUp(CapturedOutput output) {
        Fixture f = fixture();

        confirmation.giveUp(f.eventId());

        assertThat(storedResult(f.eventId())).isEqualTo("GAVE_UP");
        assertThat(jdbc.queryForObject(
                        "select order_id from payment_event_results where payment_event_id = ?",
                        UUID.class,
                        f.eventId()))
                .isEqualTo(f.orderId());
        assertThat(output.getAll()).contains("ERROR").contains(f.evt());

        provider(f, paid(f));
        confirmation.process(f.eventId());
        confirmation.giveUp(f.eventId());
        assertThat(storedResults(f.eventId())).isEqualTo(2);
        assertThat(results.findFinal(f.eventId())).contains(PaymentResult.PAID);
    }

    @Test
    @DisplayName("SEC-031: shouldPayWhenAnEventThatWasGivenUpIsProcessedAgain")
    void shouldPayWhenAnEventThatWasGivenUpIsProcessedAgain() {
        Fixture f = fixture();
        gateway.failAt("READ_CHARGE", PixGatewayException.Kind.UNAVAILABLE);
        confirmation.giveUp(f.eventId());
        gateway.failAt("READ_CHARGE", null);
        provider(f, paid(f));

        assertThat(confirmation.process(f.eventId())).isEqualTo(PaymentResult.PAID);

        assertThat(statusOf(f.orderId())).isEqualTo("PAID");
        assertThat(accesses(f)).isEqualTo(1);
    }
}
