package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OrdersTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    @Autowired
    private Orders orders;

    @Autowired
    private PaymentCustomers paymentCustomers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID customerId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        customerId = OrderTestSupport.insertCustomer(jdbc);
        productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia de Contratos", 2990);
    }

    @AfterEach
    void cleanUp() {
        // Only this test's own rows: other tests of the context leave Orders that undeletable results point to.
        OrderTestSupport.deleteOrdersOf(jdbc, customerId, productId);
    }

    private UUID insertPending(String paymentId, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        orders.insert(id, customerId, productId, 2990, paymentId, NOW, expiresAt);
        return id;
    }

    private String paymentId() {
        return "pay_" + UUID.randomUUID().toString().replace("-", "");
    }

    private Boolean tryLock(TransactionTemplate tx) {
        return tx.execute(status -> orders.tryLockCustomer(customerId));
    }

    @Test
    @DisplayName("FR-019: shouldStoreAPendingOrderAndReadItBackForItsCustomer")
    void shouldStoreAPendingOrderAndReadItBackForItsCustomer() {
        String paymentId = paymentId();
        Instant expiresAt = NOW.plus(30, ChronoUnit.MINUTES);
        UUID id = insertPending(paymentId, expiresAt);

        Orders.Row row = orders.findForCustomer(id, customerId).orElseThrow();

        assertThat(row.id()).isEqualTo(id);
        assertThat(row.customerId()).isEqualTo(customerId);
        assertThat(row.productId()).isEqualTo(productId);
        assertThat(row.productTitle()).isEqualTo("Guia de Contratos");
        assertThat(row.priceCents()).isEqualTo(2990);
        assertThat(row.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(row.providerPaymentId()).isEqualTo(paymentId);
        assertThat(row.createdAt()).isEqualTo(NOW);
        assertThat(row.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("ADR-0003: shouldHaveNoColumnForThePixCode")
    void shouldHaveNoColumnForThePixCode() {
        List<String> forbidden = jdbc.queryForList(
                "select table_name || '.' || column_name from information_schema.columns"
                        + " where table_schema = 'public' and table_name in ('orders', 'payment_customers')"
                        + " and (column_name like '%pix%' or column_name like '%copy%' or column_name like '%qr%'"
                        + " or column_name like '%payload%' or column_name like '%cpf%')",
                String.class);

        assertThat(forbidden).isEmpty();
    }

    @Test
    @DisplayName("SEC-011: shouldFindNothingForAnotherCustomer")
    void shouldFindNothingForAnotherCustomer() {
        UUID id = insertPending(paymentId(), NOW.plus(30, ChronoUnit.MINUTES));
        UUID other = OrderTestSupport.insertCustomer(jdbc);

        assertThat(orders.findForCustomer(id, other)).isEmpty();
    }

    @Test
    @DisplayName("FR-019: shouldRefuseASecondPendingOrderForTheSameCustomerAndProduct")
    void shouldRefuseASecondPendingOrderForTheSameCustomerAndProduct() {
        insertPending(paymentId(), NOW.plus(30, ChronoUnit.MINUTES));

        assertThatThrownBy(() -> insertPending(paymentId(), NOW.plus(30, ChronoUnit.MINUTES)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("FR-019: shouldRefuseTwoOrdersWithTheSamePaymentId")
    void shouldRefuseTwoOrdersWithTheSamePaymentId() {
        String paymentId = paymentId();
        insertPending(paymentId, NOW.plus(30, ChronoUnit.MINUTES));
        UUID otherProduct = OrderTestSupport.insertPublishedProduct(jdbc, "Outro", 1000);

        assertThatThrownBy(() -> orders.insert(
                        UUID.randomUUID(), customerId, otherProduct, 1000, paymentId, NOW, NOW.plusSeconds(60)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("shouldAllowANewPendingOrderAfterThePreviousOneExpired")
    void shouldAllowANewPendingOrderAfterThePreviousOneExpired() {
        UUID first = insertPending(paymentId(), NOW.plus(30, ChronoUnit.MINUTES));
        assertThat(orders.findPending(customerId, productId))
                .get()
                .extracting(Orders.Row::id)
                .isEqualTo(first);

        assertThat(orders.markExpired(first, NOW.plus(31, ChronoUnit.MINUTES))).isTrue();
        assertThat(orders.findPending(customerId, productId)).isEmpty();

        UUID second = insertPending(paymentId(), NOW.plus(60, ChronoUnit.MINUTES));
        assertThat(orders.findPending(customerId, productId))
                .get()
                .extracting(Orders.Row::id)
                .isEqualTo(second);
    }

    @Test
    @DisplayName("ADR-0003: shouldExpireOnlyOnce")
    void shouldExpireOnlyOnce() {
        UUID id = insertPending(paymentId(), NOW.plus(30, ChronoUnit.MINUTES));

        assertThat(orders.markExpired(id, NOW.plus(31, ChronoUnit.MINUTES))).isTrue();
        assertThat(orders.markExpired(id, NOW.plus(32, ChronoUnit.MINUTES))).isFalse();
        assertThat(orders.findForCustomer(id, customerId).orElseThrow().status())
                .isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    @DisplayName("shouldListOnlyPendingOrdersPastTheirDeadline")
    void shouldListOnlyPendingOrdersPastTheirDeadline() {
        UUID late = insertPending(paymentId(), NOW.minus(1, ChronoUnit.MINUTES));
        UUID alreadyExpired = UUID.randomUUID();
        orders.insert(
                alreadyExpired,
                OrderTestSupport.insertCustomer(jdbc),
                productId,
                2990,
                paymentId(),
                NOW,
                NOW.minus(2, ChronoUnit.MINUTES));
        orders.markExpired(alreadyExpired, NOW);
        UUID later = UUID.randomUUID();
        orders.insert(
                later,
                OrderTestSupport.insertCustomer(jdbc),
                productId,
                2990,
                paymentId(),
                NOW,
                NOW.minus(30, ChronoUnit.SECONDS));
        orders.insert(
                UUID.randomUUID(),
                OrderTestSupport.insertCustomer(jdbc),
                productId,
                2990,
                paymentId(),
                NOW,
                NOW.plus(5, ChronoUnit.MINUTES));

        assertThat(orders.findPendingExpired(NOW, 10))
                .filteredOn(row -> row.productId().equals(productId))
                .extracting(Orders.Row::id)
                .containsExactly(late, later);
        assertThat(orders.findPendingExpired(NOW, 1)).extracting(Orders.Row::id).containsExactly(late);
    }

    @Test
    @DisplayName("shouldGiveTheCustomerLockToOneTransactionAtATime")
    void shouldGiveTheCustomerLockToOneTransactionAtATime() throws InterruptedException {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Boolean> holder = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            boolean got = orders.tryLockCustomer(customerId);
            holding.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return got;
        }));
        try {
            holding.await();
            assertThat(tryLock(tx)).isFalse();
        } finally {
            release.countDown();
        }

        assertThat(holder.join()).isTrue();
        assertThat(tryLock(tx)).isTrue();
    }

    @Test
    @DisplayName("SEC-033: shouldKeepOnlyTheProviderCustomerCode")
    void shouldKeepOnlyTheProviderCustomerCode() {
        assertThat(paymentCustomers.find(customerId)).isEmpty();

        paymentCustomers.save(customerId, "cus_first");
        paymentCustomers.save(customerId, "cus_second");

        assertThat(paymentCustomers.find(customerId)).contains("cus_first");
    }
}
