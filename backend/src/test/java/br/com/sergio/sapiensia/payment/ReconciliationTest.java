package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * FR-022, SEC-030, SEC-028, ADR-0002, NFR-004: the nightly reconciliation compares the Asaas charges with the Orders;
 * it only schedules the existing payment-event processing or records a divergence, and never pays by itself.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
// Same annotations and properties as RefundProcessingTest, so the cached context is reused.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=payment-event-processing",
            "app.login-limit.ip-max-attempts=1000",
            "db-scheduler.polling-interval=PT0.2S",
            "app.orders.payment-processing.retry-delay=PT0.5S",
            "app.orders.payment-processing.max-attempts=5"
        })
class ReconciliationTest {

    private static final int PRICE = 2990;
    private static final BigDecimal VALUE = new BigDecimal("29.90");

    @LocalServerPort
    int port;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SwitchablePixGateway gateway;

    @Autowired
    Reconciliation reconciliation;

    @Autowired
    Orders orders;

    @Autowired
    Accesses accesses;

    @Autowired
    RecordingEmailSender sender;

    @BeforeEach
    void setUp() {
        gateway.reset();
    }

    private record Placed(UUID orderId, UUID customerId, UUID productId, String chargeId, String email) {}

    private Placed placeOrder() {
        return placeOrder("cliente-" + UUID.randomUUID() + "@example.com", "Guia " + UUID.randomUUID());
    }

    /** A pending Order written straight in the database, as if the Asaas charge had been created for it. */
    private Placed placeOrder(String email, String title) {
        UUID customer = jdbc.queryForObject(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, 'x', now()) returning id",
                UUID.class,
                email);
        UUID product = OrderTestSupport.insertPublishedProduct(jdbc, title, PRICE);
        UUID orderId = UUID.randomUUID();
        String chargeId = "pay_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        orders.insert(orderId, customer, product, PRICE, chargeId, now, now.plus(Duration.ofMinutes(30)));
        return new Placed(orderId, customer, product, chargeId, email);
    }

    private Placed placePaidOrder() {
        Placed placed = placeOrder();
        Instant now = Instant.now();
        assertThat(orders.markPaid(placed.orderId(), now)).isTrue();
        assertThat(accesses.grant(placed.customerId(), placed.productId(), placed.orderId(), now))
                .isTrue();
        return placed;
    }

    private static PixCharge charge(Placed placed, String status) {
        return new PixCharge(
                placed.chargeId(), status, VALUE, "PIX", placed.orderId().toString(), false);
    }

    private static String eventId(String type, String chargeId) {
        return "reconciliation:" + type + ":" + chargeId;
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private int events(String providerEventId) {
        return jdbc.queryForObject(
                "select count(*) from payment_events where provider_event_id = ?", Integer.class, providerEventId);
    }

    private int results(String providerEventId, String result) {
        return jdbc.queryForObject(
                "select count(*) from payment_event_results r join payment_events e on e.id = r.payment_event_id"
                        + " where e.provider_event_id = ? and r.result = ?",
                Integer.class,
                providerEventId,
                result);
    }

    private int accessRows(UUID orderId) {
        return jdbc.queryForObject("select count(*) from accesses where order_id = ?", Integer.class, orderId);
    }

    private int divergences(String kind, String chargeId) {
        return jdbc.queryForObject(
                "select count(*) from reconciliation_divergences where kind = ? and charge_id = ?",
                Integer.class,
                kind,
                chargeId);
    }

    /** The sum of the counters of the divergence alerts of the Order. */
    private int divergenceAlerts(UUID orderId) {
        return jdbc.queryForObject(
                "select coalesce(sum(occurrences), 0) from admin_alerts"
                        + " where type = 'RECONCILIATION_DIVERGENCE' and order_id = ?",
                Integer.class,
                orderId);
    }

    private int alertTotal(String type, String detail) {
        return jdbc.queryForObject(
                "select coalesce(sum(occurrences), 0) from admin_alerts where type = ? and detail = ?",
                Integer.class,
                type,
                detail);
    }

    private int auditCount(String action, UUID orderId) {
        return jdbc.queryForObject(
                "select count(*) from audit_events where action = ? and object_type = 'ORDER' and object_id = ?",
                Integer.class,
                action,
                orderId);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    private void awaitResult(String providerEventId, String result) throws InterruptedException {
        await("result " + result + " of " + providerEventId, () -> results(providerEventId, result) == 1);
    }

    private void listing(PixCharge... charges) {
        gateway.answerList(List.of(charges));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "EXPIRED"})
    @DisplayName("FR-022, SEC-030, ADR-0002: cobrança paga no Asaas com Pedido pendente vira Pedido pago com Acesso")
    void shouldPayAPendingOrCancelledOrderWhoseChargeIsPaidAtAsaas(String initial) throws Exception {
        Placed placed = placeOrder();
        if (initial.equals("EXPIRED")) {
            assertThat(orders.markExpired(placed.orderId(), Instant.now())).isTrue();
        }
        gateway.answerCharge(placed.chargeId(), charge(placed, "RECEIVED"));
        listing(charge(placed, "RECEIVED"));

        Reconciliation.Result result = reconciliation.run();

        String event = eventId("PAYMENT_RECEIVED", placed.chargeId());
        assertThat(result.paymentFixes()).isEqualTo(1);
        awaitResult(event, "PAID");
        assertThat(status(placed.orderId())).isEqualTo("PAID");
        assertThat(accessRows(placed.orderId())).isEqualTo(1);
        assertThat(sender.awaitMessageTo(placed.email(), Duration.ofSeconds(15)))
                .isPresent();
        assertThat(sender.messagesTo(placed.email())).hasSize(1);
        assertThat(auditCount("ACCESS_GRANTED", placed.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-022, SEC-030: cobrança estornada no Asaas com Pedido pago vira reembolsado e o Acesso é cancelado")
    void shouldRefundAPaidOrderWhoseChargeIsRefundedAtAsaas() throws Exception {
        Placed placed = placePaidOrder();
        gateway.answerCharge(placed.chargeId(), charge(placed, "REFUNDED"));
        listing(charge(placed, "REFUNDED"));

        Reconciliation.Result result = reconciliation.run();

        assertThat(result.refundFixes()).isEqualTo(1);
        awaitResult(eventId("PAYMENT_REFUNDED", placed.chargeId()), "REFUNDED");
        assertThat(status(placed.orderId())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                        "select cancelled_at is not null from accesses where order_id = ?",
                        Boolean.class,
                        placed.orderId()))
                .isTrue();
        assertThat(auditCount("ACCESS_CANCELLED", placed.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-022: valor diferente não paga o Pedido e gera o alerta de revisão AMOUNT_MISMATCH")
    void shouldNotPayWhenTheAmountDiffersAndRaiseTheReviewAlert() throws Exception {
        Placed placed = placeOrder();
        PixCharge wrong = new PixCharge(
                placed.chargeId(),
                "RECEIVED",
                new BigDecimal("1.00"),
                "PIX",
                placed.orderId().toString(),
                false);
        gateway.answerCharge(placed.chargeId(), wrong);
        listing(wrong);

        reconciliation.run();

        awaitResult(eventId("PAYMENT_RECEIVED", placed.chargeId()), "AMOUNT_MISMATCH");
        assertThat(status(placed.orderId())).isEqualTo("PENDING");
        assertThat(accessRows(placed.orderId())).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from admin_alerts where type = 'PAYMENT_EVENT_NEEDS_REVIEW'"
                                + " and detail = 'AMOUNT_MISMATCH' and order_id = ?",
                        Integer.class,
                        placed.orderId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-0002: cobrança paga sem Pedido só alerta; nenhum Pedido é criado")
    void shouldOnlyAlertWhenAPaidChargeHasNoOrder() {
        String chargeId = "pay_" + UUID.randomUUID().toString().replace("-", "");
        int ordersBefore = jdbc.queryForObject("select count(*) from orders", Integer.class);
        int alertsBefore = alertTotal("RECONCILIATION_DIVERGENCE", "CHARGE_WITHOUT_ORDER");
        listing(new PixCharge(
                chargeId, "RECEIVED", VALUE, "PIX", UUID.randomUUID().toString(), false));

        reconciliation.run();

        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class))
                .isEqualTo(ordersBefore);
        assertThat(divergences("CHARGE_WITHOUT_ORDER", chargeId)).isEqualTo(1);
        assertThat(alertTotal("RECONCILIATION_DIVERGENCE", "CHARGE_WITHOUT_ORDER"))
                .isEqualTo(alertsBefore + 1);
        assertThat(events(eventId("PAYMENT_RECEIVED", chargeId))).isZero();
    }

    @Test
    @DisplayName("FR-022: Pedido pago com cobrança não paga, ou que o Asaas não acha, gera divergência")
    void shouldRecordADivergenceWhenAPaidOrderChargeIsNotPaidOrNotFound() {
        Placed notPaid = placePaidOrder();
        Placed notFound = placePaidOrder();
        gateway.answerCharge(notPaid.chargeId(), charge(notPaid, "PENDING"));
        // notFound: the listing does not have it and the provider answers 404 (the fake does not know the charge)
        listing(charge(notPaid, "PENDING"));

        reconciliation.run();

        assertThat(divergences("ORDER_PAID_CHARGE_NOT_PAID", notPaid.chargeId()))
                .isEqualTo(1);
        assertThat(divergenceAlerts(notPaid.orderId())).isEqualTo(1);
        assertThat(divergences("ORDER_PAID_CHARGE_NOT_FOUND", notFound.chargeId()))
                .isEqualTo(1);
        assertThat(divergenceAlerts(notFound.orderId())).isEqualTo(1);
        assertThat(status(notPaid.orderId())).isEqualTo("PAID");
        assertThat(status(notFound.orderId())).isEqualTo("PAID");
    }

    @Test
    @DisplayName("FR-022: Pedido reembolsado com cobrança paga gera divergência")
    void shouldRecordADivergenceWhenARefundedOrderChargeIsPaid() {
        Placed placed = placePaidOrder();
        assertThat(orders.markRefunded(placed.orderId(), Instant.now())).isTrue();
        gateway.answerCharge(placed.chargeId(), charge(placed, "RECEIVED"));
        listing(charge(placed, "RECEIVED"));

        reconciliation.run();

        assertThat(divergences("ORDER_REFUNDED_CHARGE_PAID", placed.chargeId())).isEqualTo(1);
        assertThat(divergenceAlerts(placed.orderId())).isEqualTo(1);
        assertThat(status(placed.orderId())).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("SEC-030: rodar duas vezes seguidas não duplica evento, Acesso, e-mail, divergência nem alerta")
    void shouldNotDuplicateAnythingWhenRunTwice() throws Exception {
        Placed toPay = placeOrder();
        gateway.answerCharge(toPay.chargeId(), charge(toPay, "RECEIVED"));
        Placed diverging = placePaidOrder();
        gateway.answerCharge(diverging.chargeId(), charge(diverging, "PENDING"));
        listing(charge(toPay, "RECEIVED"), charge(diverging, "PENDING"));

        reconciliation.run();
        String event = eventId("PAYMENT_RECEIVED", toPay.chargeId());
        awaitResult(event, "PAID");
        assertThat(sender.awaitMessageTo(toPay.email(), Duration.ofSeconds(15))).isPresent();
        Reconciliation.Result second = reconciliation.run();

        assertThat(second.paymentFixes()).isZero();
        assertThat(events(event)).isEqualTo(1);
        assertThat(accessRows(toPay.orderId())).isEqualTo(1);
        assertThat(sender.messagesTo(toPay.email())).hasSize(1);
        assertThat(divergences("ORDER_PAID_CHARGE_NOT_PAID", diverging.chargeId()))
                .isEqualTo(1);
        assertThat(divergenceAlerts(diverging.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-030: evento da conciliação que desistiu é reagendado na noite seguinte")
    void shouldRescheduleAGaveUpEventTheNextNight() throws Exception {
        Placed placed = placeOrder();
        // the listing says paid, but the provider still reads the charge as waiting: the task retries and gives up
        gateway.answerCharge(placed.chargeId(), charge(placed, "PENDING"));
        listing(charge(placed, "RECEIVED"));
        String event = eventId("PAYMENT_RECEIVED", placed.chargeId());
        reconciliation.run();
        awaitResult(event, "GAVE_UP");

        gateway.answerCharge(placed.chargeId(), charge(placed, "RECEIVED"));
        Reconciliation.Result nextNight = reconciliation.run();

        assertThat(nextNight.paymentFixes()).isEqualTo(1);
        awaitResult(event, "PAID");
        assertThat(events(event)).isEqualTo(1);
        assertThat(status(placed.orderId())).isEqualTo("PAID");
        assertThat(accessRows(placed.orderId())).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-030: id de cobrança fora do padrão é ignorado e nunca chega a SQL, log ou evento")
    void shouldIgnoreAChargeIdOutsideThePattern(CapturedOutput output) {
        String marker = "bad" + UUID.randomUUID().toString().replace("-", "");
        String tooLong = "pay_" + "x".repeat(65);
        String withQuote = "pay_a'b" + marker;
        listing(
                new PixCharge(
                        tooLong, "RECEIVED", VALUE, "PIX", UUID.randomUUID().toString(), false),
                new PixCharge(
                        withQuote, "RECEIVED", VALUE, "PIX", UUID.randomUUID().toString(), false));

        Reconciliation.Result result = reconciliation.run();

        assertThat(result.ignored()).isEqualTo(2);
        assertThat(result.charges()).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from payment_events where provider_event_id like ?",
                        Integer.class,
                        "reconciliation:%" + marker + "%"))
                .isZero();
        assertThat(output.getAll()).doesNotContain(marker).doesNotContain(tooLong);
    }

    @Test
    @DisplayName("SEC-030: cobrança paga sem Pedido e sem referência em UUID não alerta e não quebra")
    void shouldNotAlertWhenAPaidChargeWithoutOrderHasNoUuidReference() {
        String withNull = "pay_" + UUID.randomUUID().toString().replace("-", "");
        String withText = "pay_" + UUID.randomUUID().toString().replace("-", "");
        listing(
                new PixCharge(withNull, "RECEIVED", VALUE, "PIX", null, false),
                new PixCharge(withText, "CONFIRMED", VALUE, "PIX", "not-a-uuid", false));

        Reconciliation.Result result = reconciliation.run();

        assertThat(result.ignored()).isEqualTo(2);
        assertThat(divergences("CHARGE_WITHOUT_ORDER", withNull)).isZero();
        assertThat(divergences("CHARGE_WITHOUT_ORDER", withText)).isZero();
    }

    @Test
    @DisplayName("SEC-030: Asaas fora na segunda página mantém a correção da primeira, alerta e falha a execução")
    void shouldKeepThePageOneFixAndFailWhenAsaasFailsOnPageTwo() throws Exception {
        Placed placed = placeOrder();
        gateway.answerCharge(placed.chargeId(), charge(placed, "RECEIVED"));
        List<PixCharge> all = new ArrayList<>();
        all.add(charge(placed, "RECEIVED"));
        for (int i = 0; i < 100; i++) {
            all.add(new PixCharge(
                    "pay_f" + i + UUID.randomUUID().toString().replace("-", ""), "PENDING", VALUE, "PIX", null, false));
        }
        gateway.answerList(all);
        gateway.failListFromOffset(100);
        int failedBefore = alertTotal("RECONCILIATION_FAILED", "ASAAS_UNAVAILABLE");

        assertThatThrownBy(() -> reconciliation.run())
                .isInstanceOfSatisfying(
                        Reconciliation.IncompleteException.class,
                        e -> assertThat(e.reason()).isEqualTo("ASAAS_UNAVAILABLE"));

        assertThat(events(eventId("PAYMENT_RECEIVED", placed.chargeId()))).isEqualTo(1);
        awaitResult(eventId("PAYMENT_RECEIVED", placed.chargeId()), "PAID");
        assertThat(alertTotal("RECONCILIATION_FAILED", "ASAAS_UNAVAILABLE")).isEqualTo(failedBefore + 1);
    }

    @Test
    @DisplayName("SEC-030: listagem que nunca termina para em max-pages com TOO_MANY_CHARGES")
    void shouldStopAtMaxPagesWhenTheListingNeverEnds() {
        gateway.endlessList(new PixCharge("pay_endless", "PENDING", VALUE, "PIX", null, false));
        int failedBefore = alertTotal("RECONCILIATION_FAILED", "TOO_MANY_CHARGES");

        assertThatThrownBy(() -> reconciliation.run())
                .isInstanceOfSatisfying(
                        Reconciliation.IncompleteException.class,
                        e -> assertThat(e.reason()).isEqualTo("TOO_MANY_CHARGES"));

        assertThat(gateway.count("LIST_CHARGES")).isEqualTo(300);
        assertThat(alertTotal("RECONCILIATION_FAILED", "TOO_MANY_CHARGES")).isEqualTo(failedBefore + 1);
    }

    @Test
    @DisplayName("SEC-030: a mesma cobrança em duas páginas gera um evento e uma divergência só")
    void shouldHandleAChargeRepeatedAcrossPagesOnce() throws Exception {
        Placed toPay = placeOrder();
        gateway.answerCharge(toPay.chargeId(), charge(toPay, "RECEIVED"));
        Placed diverging = placePaidOrder();
        gateway.answerCharge(diverging.chargeId(), charge(diverging, "PENDING"));
        List<PixCharge> all = new ArrayList<>();
        all.add(charge(toPay, "RECEIVED"));
        all.add(charge(diverging, "PENDING"));
        for (int i = 0; i < 98; i++) {
            all.add(new PixCharge(
                    "pay_g" + i + UUID.randomUUID().toString().replace("-", ""), "PENDING", VALUE, "PIX", null, false));
        }
        all.add(charge(toPay, "RECEIVED"));
        all.add(charge(diverging, "PENDING"));
        gateway.answerList(all);

        Reconciliation.Result result = reconciliation.run();

        assertThat(result.paymentFixes()).isEqualTo(1);
        assertThat(events(eventId("PAYMENT_RECEIVED", toPay.chargeId()))).isEqualTo(1);
        assertThat(divergences("ORDER_PAID_CHARGE_NOT_PAID", diverging.chargeId()))
                .isEqualTo(1);
        assertThat(divergenceAlerts(diverging.orderId())).isEqualTo(1);
        awaitResult(eventId("PAYMENT_RECEIVED", toPay.chargeId()), "PAID");
    }

    @Test
    @DisplayName(
            "SEC-028, FR-022: o evento da conciliação se distingue do webhook pelo id e pelo payload e não pode ser alterado")
    void shouldMarkTheReconciliationEventAndKeepItImmutable() throws Exception {
        Placed placed = placeOrder();
        gateway.answerCharge(placed.chargeId(), charge(placed, "RECEIVED"));
        listing(charge(placed, "RECEIVED"));
        reconciliation.run();
        String event = eventId("PAYMENT_RECEIVED", placed.chargeId());
        awaitResult(event, "PAID");

        assertThat(jdbc.queryForObject(
                        "select payload->>'source' from payment_events where provider_event_id = ?",
                        String.class,
                        event))
                .isEqualTo("RECONCILIATION");
        assertThat(jdbc.queryForObject(
                        "select payload->'payment'->>'id' from payment_events where provider_event_id = ?",
                        String.class,
                        event))
                .isEqualTo(placed.chargeId());
        assertThat(jdbc.queryForObject(
                        "select count(*) from payment_events where provider_event_id not like 'reconciliation:%'"
                                + " and payload->>'source' = 'RECONCILIATION'",
                        Integer.class))
                .isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "update payment_events set event_type = 'PAYMENT_REFUNDED' where provider_event_id = ?", event))
                .rootCause()
                .isInstanceOfSatisfying(
                        PSQLException.class,
                        e -> assertThat(e.getServerErrorMessage().getMessage())
                                .isEqualTo("payment_events is append-only"));
        assertThatThrownBy(() -> jdbc.update("delete from payment_events where provider_event_id = ?", event))
                .rootCause()
                .isInstanceOfSatisfying(
                        PSQLException.class,
                        e -> assertThat(e.getServerErrorMessage().getMessage())
                                .isEqualTo("payment_events is append-only"));
        assertThat(events(event)).isEqualTo(1);
    }

    private void failReads() {
        gateway.failAt("READ_CHARGE", PixGatewayException.Kind.UNAVAILABLE);
    }

    private void assertStoppedAsUnavailable(String chargeId) {
        int failedBefore = alertTotal("RECONCILIATION_FAILED", "ASAAS_UNAVAILABLE");

        assertThatThrownBy(() -> reconciliation.run())
                .isInstanceOfSatisfying(
                        Reconciliation.IncompleteException.class,
                        e -> assertThat(e.reason()).isEqualTo("ASAAS_UNAVAILABLE"));

        assertThat(alertTotal("RECONCILIATION_FAILED", "ASAAS_UNAVAILABLE")).isEqualTo(failedBefore + 1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from reconciliation_divergences where charge_id = ?", Integer.class, chargeId))
                .isZero();
    }

    @Test
    @DisplayName(
            "SEC-030: Asaas fora na leitura de um Pedido pago que não veio na listagem para a execução, sem divergência")
    void shouldStopWhenTheFallbackReadOfAPaidOrderIsUnavailable() {
        Placed placed = placePaidOrder();
        listing();
        failReads();

        assertStoppedAsUnavailable(placed.chargeId());
    }

    @Test
    @DisplayName("SEC-030: Asaas fora na confirmação de um Pedido reembolsado para a execução, sem divergência")
    void shouldStopWhenTheConfirmationReadOfARefundedOrderIsUnavailable() {
        Placed placed = placePaidOrder();
        assertThat(orders.markRefunded(placed.orderId(), Instant.now())).isTrue();
        listing(charge(placed, "RECEIVED"));
        failReads();

        assertStoppedAsUnavailable(placed.chargeId());
    }

    @Test
    @DisplayName("FR-022, SEC-030: Pedido pago fora da listagem cuja cobrança lida está estornada vira reembolsado")
    void shouldRefundAPaidOrderMissingFromTheListingWhenTheReadSaysRefunded() throws Exception {
        Placed placed = placePaidOrder();
        gateway.answerCharge(placed.chargeId(), charge(placed, "REFUNDED"));
        listing();

        Reconciliation.Result result = reconciliation.run();

        assertThat(result.refundFixes()).isEqualTo(1);
        awaitResult(eventId("PAYMENT_REFUNDED", placed.chargeId()), "REFUNDED");
        assertThat(status(placed.orderId())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                        "select cancelled_at is not null from accesses where order_id = ?",
                        Boolean.class,
                        placed.orderId()))
                .isTrue();
    }

    @Test
    @DisplayName("FR-022: Pedido pago fora da listagem cuja cobrança lida está paga não gera evento nem divergência")
    void shouldDoNothingForAPaidOrderMissingFromTheListingWhenTheReadSaysPaid() {
        Placed placed = placePaidOrder();
        gateway.answerCharge(placed.chargeId(), charge(placed, "RECEIVED"));
        listing();

        reconciliation.run();

        assertThat(events(eventId("PAYMENT_RECEIVED", placed.chargeId()))).isZero();
        assertThat(events(eventId("PAYMENT_REFUNDED", placed.chargeId()))).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from reconciliation_divergences where charge_id = ?",
                        Integer.class,
                        placed.chargeId()))
                .isZero();
        assertThat(status(placed.orderId())).isEqualTo("PAID");
    }

    @Test
    @DisplayName(
            "FR-022: listagem atrasada (paga) com Pedido reembolsado e cobrança lida não paga não gera divergência")
    void shouldNotRecordADivergenceWhenTheListingIsLate() {
        Placed placed = placePaidOrder();
        assertThat(orders.markRefunded(placed.orderId(), Instant.now())).isTrue();
        listing(charge(placed, "RECEIVED"));
        gateway.answerCharge(placed.chargeId(), charge(placed, "REFUNDED"));

        reconciliation.run();

        assertThat(jdbc.queryForObject(
                        "select count(*) from reconciliation_divergences where charge_id = ?",
                        Integer.class,
                        placed.chargeId()))
                .isZero();
        assertThat(divergenceAlerts(placed.orderId())).isZero();
    }

    @Test
    @DisplayName("SEC-030: o log da conciliação só traz códigos, ids e contagens")
    void shouldLogOnlyCodesIdsAndCounts(CapturedOutput output) throws Exception {
        String cpf = "52998224725";
        // a real purchase, so the e-mail and the CPF go through the system to the fake provider
        OrderHttp.Customer customer = OrderHttp.newCustomer(port, jdbc, encoder);
        String title = "marker-title-" + UUID.randomUUID();
        UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, title, PRICE);
        var created = customer.order(productId, cpf);
        assertThat(created.statusCode()).isEqualTo(201);
        UUID orderId = UUID.fromString(OrderHttp.json(created, "$.id"));
        String chargeId =
                jdbc.queryForObject("select provider_payment_id from orders where id = ?", String.class, orderId);
        gateway.reset();
        String email = customer.email;
        Placed toPay = new Placed(orderId, customer.id, productId, chargeId, email);
        gateway.answerCharge(toPay.chargeId(), charge(toPay, "RECEIVED"));
        Placed diverging = placePaidOrder();
        gateway.answerCharge(diverging.chargeId(), charge(diverging, "PENDING"));
        String orphan = "pay_" + UUID.randomUUID().toString().replace("-", "");
        // free text the provider could send in the reference of a charge this system did not create
        String referenceMarker = "ref-marker-" + UUID.randomUUID();
        listing(
                charge(toPay, "RECEIVED"),
                charge(diverging, "PENDING"),
                new PixCharge(
                        orphan, "RECEIVED", VALUE, "PIX", UUID.randomUUID().toString(), false),
                new PixCharge(
                        "pay_" + UUID.randomUUID().toString().replace("-", ""),
                        "RECEIVED",
                        VALUE,
                        "PIX",
                        referenceMarker,
                        false));
        Instant start = Instant.now();

        reconciliation.run();
        awaitResult(eventId("PAYMENT_RECEIVED", toPay.chargeId()), "PAID");

        List<String> lines = output.getAll()
                .lines()
                .filter(l -> l.contains("Reconciliation"))
                .toList();
        assertThat(lines).anyMatch(l -> l.contains("Reconciliation finished: charges="));
        assertThat(lines)
                .anyMatch(l -> l.contains("Reconciliation divergence: kind=CHARGE_WITHOUT_ORDER chargeId=" + orphan));
        assertThat(lines)
                .allSatisfy(l -> assertThat(l)
                        .doesNotContain(email)
                        .doesNotContain(title)
                        .doesNotContain(cpf)
                        .doesNotContain(referenceMarker));
        assertThat(lines)
                .filteredOn(l -> l.contains("Reconciliation finished"))
                .allMatch(l -> l.matches(".*Reconciliation finished: charges=\\d+ paymentFixes=\\d+ refundFixes=\\d+"
                        + " divergences=\\d+ ignored=\\d+\\s*"));
        assertThat(jdbc.queryForList(
                        "select distinct detail from admin_alerts"
                                + " where type in ('RECONCILIATION_DIVERGENCE', 'RECONCILIATION_FAILED')"
                                + " and occurred_at >= ?",
                        String.class,
                        java.sql.Timestamp.from(start)))
                .isNotEmpty()
                .allMatch(d -> List.of(
                                "CHARGE_WITHOUT_ORDER",
                                "ORDER_PAID_CHARGE_NOT_PAID",
                                "ORDER_PAID_CHARGE_NOT_FOUND",
                                "ORDER_REFUNDED_CHARGE_PAID",
                                "ASAAS_UNAVAILABLE",
                                "TOO_MANY_CHARGES")
                        .contains(d));
    }
}
