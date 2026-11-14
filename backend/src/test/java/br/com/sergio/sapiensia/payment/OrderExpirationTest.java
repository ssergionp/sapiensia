package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** ADR-0003: a pending Order lives 30 minutes, then the charge is cancelled and the Order expires. */
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class, OrderExpirationTest.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=order-expiration",
            "app.login-limit.ip-max-attempts=1000",
            // The test calls the Expirer itself; the real scheduler (needed by other beans, so not disabled) polls only
            // once an hour, so it never expires Orders between the steps.
            "db-scheduler.polling-interval=PT1H"
        })
class OrderExpirationTest {

    private static final String CPF = "52998224725";
    private static final Duration TTL = Duration.ofMinutes(30);

    /** A clock the test moves by hand; read by the server threads, so it is thread-safe. */
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
        MutableClock orderTestClock() {
            return new MutableClock();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SwitchablePixGateway gateway;

    @Autowired
    MutableClock clock;

    @Autowired
    OrderExpiration.Expirer expirer;

    @Autowired
    ApplicationContext context;

    private OrderHttp.Customer customer;
    private UUID productId;

    @BeforeEach
    void setUp() throws Exception {
        gateway.reset();
        customer = OrderHttp.newCustomer(port, jdbc, encoder);
        productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia de Contratos", 2990);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from orders");
        jdbc.update("delete from payment_customers");
    }

    private String createOrder() throws Exception {
        HttpResponse<String> answer = customer.order(productId, CPF);
        assertThat(answer.statusCode()).isEqualTo(201);
        return OrderHttp.json(answer, "$.id");
    }

    private String statusOf(String orderId) {
        return jdbc.queryForObject("select status from orders where id = ?::uuid", String.class, orderId);
    }

    private long orderRows() {
        return jdbc.queryForObject("select count(*) from orders", Long.class);
    }

    @Test
    @DisplayName("ADR-0003: shouldExpireAndCancelTheChargeAfterThirtyMinutes")
    void shouldExpireAndCancelTheChargeAfterThirtyMinutes() throws Exception {
        String id = createOrder();
        String chargeId = gateway.lastChargeId();

        clock.advance(TTL.minusSeconds(1));
        OrderExpiration.Expirer.Result early = expirer.run();
        assertThat(early.expired()).isZero();
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(gateway.cancelledCharges()).isEmpty();

        clock.advance(Duration.ofSeconds(1));
        OrderExpiration.Expirer.Result due = expirer.run();

        assertThat(due.expired()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("EXPIRED");
        assertThat(gateway.cancelledCharges()).containsExactly(chargeId);
    }

    @Test
    @DisplayName("ADR-0003: shouldShowAnOrderAsExpiredBeforeTheTaskRuns")
    void shouldShowAnOrderAsExpiredBeforeTheTaskRuns() throws Exception {
        String id = createOrder();
        clock.advance(TTL.plusSeconds(1));
        long readsBefore = gateway.copyPasteReads();

        HttpResponse<String> answer = customer.get("/api/orders/" + id);

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(OrderHttp.json(answer, "$.status")).isEqualTo("EXPIRED");
        assertThat(OrderHttp.json(answer, "$.secondsToExpire")).isEqualTo("0");
        assertThat(OrderHttp.json(answer, "$.pixCopyPaste")).isNull();
        assertThat(gateway.copyPasteReads()).isEqualTo(readsBefore);
    }

    @Test
    @DisplayName("ADR-0003: shouldCreateANewOrderAfterThePreviousOneExpired")
    void shouldCreateANewOrderAfterThePreviousOneExpired() throws Exception {
        String oldId = createOrder();
        String oldCharge = gateway.lastChargeId();
        clock.advance(TTL.plusSeconds(1));

        HttpResponse<String> answer = customer.order(productId, CPF);

        assertThat(answer.statusCode()).isEqualTo(201);
        String newId = OrderHttp.json(answer, "$.id");
        assertThat(newId).isNotEqualTo(oldId);
        assertThat(gateway.cancelledCharges()).containsExactly(oldCharge);
        assertThat(statusOf(oldId)).isEqualTo("EXPIRED");
        assertThat(statusOf(newId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("ADR-0003: shouldRetryLaterWhenTheProviderIsUnavailable")
    void shouldRetryLaterWhenTheProviderIsUnavailable() throws Exception {
        String id = createOrder();
        clock.advance(TTL.plusSeconds(1));
        gateway.failCancel(PixGatewayException.Kind.UNAVAILABLE);

        OrderExpiration.Expirer.Result first = expirer.run();

        assertThat(first.expired()).isZero();
        assertThat(first.postponed()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("PENDING");

        gateway.failCancel(null);
        OrderExpiration.Expirer.Result second = expirer.run();

        assertThat(second.expired()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("ADR-0003: shouldStopAtTheFirstOutageAndPostponeTheRest")
    void shouldStopAtTheFirstOutageAndPostponeTheRest() throws Exception {
        String first = createOrder();
        OrderHttp.Customer other = OrderHttp.newCustomer(port, jdbc, encoder);
        assertThat(other.order(productId, CPF).statusCode()).isEqualTo(201);
        clock.advance(TTL.plusSeconds(1));
        gateway.failCancel(PixGatewayException.Kind.UNAVAILABLE);

        OrderExpiration.Expirer.Result result = expirer.run();

        assertThat(result.expired()).isZero();
        assertThat(result.postponed()).isEqualTo(2);
        assertThat(gateway.count("CANCEL_CHARGE")).isEqualTo(1);
        assertThat(statusOf(first)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("ADR-0003: shouldExpireAnywayWhenTheProviderRefusesToCancel")
    void shouldExpireAnywayWhenTheProviderRefusesToCancel() throws Exception {
        String id = createOrder();
        clock.advance(TTL.plusSeconds(1));
        gateway.failCancel(PixGatewayException.Kind.REFUSED);

        OrderExpiration.Expirer.Result result = expirer.run();

        assertThat(result.expired()).isEqualTo(1);
        assertThat(result.postponed()).isZero();
        assertThat(statusOf(id)).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("ADR-0003: shouldAnswer502WhenAnExpiredOrderCannotBeCancelledBeforeANewOne")
    void shouldAnswer502WhenAnExpiredOrderCannotBeCancelledBeforeANewOne() throws Exception {
        String id = createOrder();
        clock.advance(TTL.plusSeconds(1));
        gateway.failCancel(PixGatewayException.Kind.UNAVAILABLE);

        HttpResponse<String> answer = customer.order(productId, CPF);

        assertThat(answer.statusCode()).isEqualTo(502);
        assertThat(orderRows()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("ADR-0003: shouldLeaveOrdersStillInTimeAlone")
    void shouldLeaveOrdersStillInTimeAlone() throws Exception {
        String id = createOrder();
        clock.advance(Duration.ofMinutes(10));

        OrderExpiration.Expirer.Result result = expirer.run();

        assertThat(result.expired()).isZero();
        assertThat(result.postponed()).isZero();
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(gateway.cancelledCharges()).isEmpty();
    }

    @Test
    @DisplayName("ADR-0003: shouldRegisterTheRecurringTask")
    void shouldRegisterTheRecurringTask() {
        RecurringTask<?> task = context.getBean("expirePendingOrdersTask", RecurringTask.class);

        assertThat(task.getName()).isEqualTo("expire-pending-orders");
    }
}
