package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentEventsTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-11T12:00:00Z");

    @Autowired
    private PaymentEvents events;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("FR-021: shouldStoreTheEventWithItsRawPayload")
    void shouldStoreTheEventWithItsRawPayload() {
        String id = "evt_" + UUID.randomUUID() + "&1";
        assertThat(events.insertIfAbsent(id, "PAYMENT_RECEIVED", "{\"id\":\"x\",\"payment\":{\"value\":10.5}}", NOW))
                .isPresent();
        Map<String, Object> row = jdbc.queryForMap(
                "select event_type, payload::text as payload, received_at from payment_events where provider_event_id = ?",
                id);
        assertThat(row.get("event_type")).isEqualTo("PAYMENT_RECEIVED");
        assertThat((String) row.get("payload")).contains("\"value\": 10.5");
    }

    @Test
    @DisplayName("SEC-025: shouldInsertNothingWhenTheEventIdAlreadyExists")
    void shouldInsertNothingWhenTheEventIdAlreadyExists() {
        String id = "evt_" + UUID.randomUUID();
        assertThat(events.insertIfAbsent(id, "PAYMENT_RECEIVED", "{\"a\":1}", NOW))
                .isPresent();
        assertThat(events.insertIfAbsent(id, "PAYMENT_REFUNDED", "{\"a\":2}", NOW))
                .isEmpty();
        assertThat(jdbc.queryForObject(
                        "select count(*) from payment_events where provider_event_id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select event_type from payment_events where provider_event_id = ?", String.class, id))
                .isEqualTo("PAYMENT_RECEIVED");
    }

    @Test
    @DisplayName("ADR-0003: shouldRefuseAnEventTypeOutsideTheUpperCaseAlphabet")
    void shouldRefuseAnEventTypeOutsideTheUpperCaseAlphabet() {
        assertThatThrownBy(() -> events.insertIfAbsent("evt_" + UUID.randomUUID(), "payment received", "{}", NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("FR-021: shouldReturnTheInternalIdOfAStoredEventAndNothingForARepeatedOne")
    void shouldReturnTheInternalIdOfAStoredEventAndNothingForARepeatedOne() {
        String id = "evt_" + UUID.randomUUID();
        UUID internalId =
                events.insertIfAbsent(id, "PAYMENT_RECEIVED", "{}", NOW).orElseThrow();

        assertThat(jdbc.queryForObject("select id from payment_events where provider_event_id = ?", UUID.class, id))
                .isEqualTo(internalId);
        assertThat(events.insertIfAbsent(id, "PAYMENT_RECEIVED", "{}", NOW)).isEmpty();
    }

    @Test
    @DisplayName("shouldReadOnlyTheChargeIdOfAnEvent")
    void shouldReadOnlyTheChargeIdOfAnEvent() {
        String withCharge = "evt_" + UUID.randomUUID();
        String without = "evt_" + UUID.randomUUID();
        UUID a = events.insertIfAbsent(withCharge, "PAYMENT_RECEIVED", "{\"payment\":{\"id\":\"pay_123\"}}", NOW)
                .orElseThrow();
        UUID b = events.insertIfAbsent(without, "PAYMENT_RECEIVED", "{\"payment\":{}}", NOW)
                .orElseThrow();

        assertThat(events.findRef(a)).contains(new PaymentEvents.Ref(withCharge, "PAYMENT_RECEIVED", "pay_123", null));
        assertThat(events.findRef(b)).contains(new PaymentEvents.Ref(without, "PAYMENT_RECEIVED", null, null));
        assertThat(events.findRef(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("FR-025: shouldReadTheChargeIdAndTheTypeOfABalanceBlock")
    void shouldReadTheChargeIdAndTheTypeOfABalanceBlock() {
        String withCharge = "evt_" + UUID.randomUUID();
        String without = "evt_" + UUID.randomUUID();
        UUID a = events.insertIfAbsent(
                        withCharge,
                        "BALANCE_VALUE_BLOCKED",
                        "{\"balanceData\":{\"type\":\"PIX_INFRACTION\",\"paymentId\":\"pay_456\"}}",
                        NOW)
                .orElseThrow();
        UUID b = events.insertIfAbsent(
                        without,
                        "BALANCE_VALUE_BLOCKED",
                        "{\"balanceData\":{\"type\":\"PIX_INFRACTION\",\"paymentId\":null}}",
                        NOW)
                .orElseThrow();

        assertThat(events.findRef(a))
                .contains(new PaymentEvents.Ref(withCharge, "BALANCE_VALUE_BLOCKED", "pay_456", "PIX_INFRACTION"));
        assertThat(events.findRef(b))
                .contains(new PaymentEvents.Ref(without, "BALANCE_VALUE_BLOCKED", null, "PIX_INFRACTION"));
    }

    @Test
    @DisplayName("SEC-026: shouldNotTakeTheChargeIdFromBalanceDataOutsideABalanceBlock")
    void shouldNotTakeTheChargeIdFromBalanceDataOutsideABalanceBlock() {
        for (String type : new String[] {"PAYMENT_RECEIVED", "PAYMENT_CONFIRMED", "PAYMENT_REFUNDED"}) {
            String eventId = "evt_" + UUID.randomUUID();
            UUID id = events.insertIfAbsent(
                            eventId, type, "{\"balanceData\":{\"type\":\"PIX_INFRACTION\",\"paymentId\":\"pay_789\"}}", NOW)
                    .orElseThrow();

            assertThat(events.findRef(id)).contains(new PaymentEvents.Ref(eventId, type, null, "PIX_INFRACTION"));
        }
    }
}
