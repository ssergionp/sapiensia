package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.sergio.sapiensia.product.CatalogProduct;
import br.com.sergio.sapiensia.product.CatalogService;
import br.com.sergio.sapiensia.product.ProductCategory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** A2: the charge exists at the provider but the Order cannot be stored. */
class OrderServiceStoreFailureTest {

    private final UUID customerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    private Orders orders;
    private PaymentCustomers paymentCustomers;
    private PixGateway gateway;
    private OrderService service;

    @BeforeEach
    void setUp() {
        orders = mock(Orders.class);
        paymentCustomers = mock(PaymentCustomers.class);
        gateway = mock(PixGateway.class);
        CatalogService catalog = mock(CatalogService.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        when(orders.tryLockCustomer(customerId)).thenReturn(true);
        when(orders.findPending(customerId, productId)).thenReturn(Optional.empty());
        when(catalog.get(productId))
                .thenReturn(new CatalogProduct(productId, "Guia", "d", ProductCategory.LAW, 2990, null));
        when(paymentCustomers.find(customerId)).thenReturn(Optional.of("cus_1"));
        when(gateway.createCharge(eq("cus_1"), any(), anyInt(), any(), any())).thenReturn("pay_created");

        Clock clock = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);
        service = new OrderService(
                orders,
                paymentCustomers,
                gateway,
                catalog,
                new TransactionTemplate(manager),
                clock,
                new OrderExpiration.Expirer(orders, gateway, clock),
                Duration.ofMinutes(30));
    }

    private void databaseRefusesTheOrder(RuntimeException failure) {
        doThrow(failure)
                .when(orders)
                .insert(any(), eq(customerId), eq(productId), anyInt(), eq("pay_created"), any(), any());
    }

    @Test
    @DisplayName("ADR-0003: shouldCancelTheChargeWhenTheDatabaseRefusesTheOrder")
    void shouldCancelTheChargeWhenTheDatabaseRefusesTheOrder() {
        var failure = new DataAccessResourceFailureException("db down");
        databaseRefusesTheOrder(failure);

        assertThatThrownBy(() -> service.create(customerId, "c@example.com", new CreateOrderRequest(productId, null)))
                .isSameAs(failure);

        ArgumentCaptor<String> cancelled = ArgumentCaptor.forClass(String.class);
        verify(gateway).cancelCharge(cancelled.capture());
        assertThat(cancelled.getValue()).isEqualTo("pay_created");
    }

    @Test
    @DisplayName("ADR-0003: shouldRethrowTheSameErrorWhenTheCancelFailsToo")
    void shouldRethrowTheSameErrorWhenTheCancelFailsToo() {
        var failure = new DataAccessResourceFailureException("db down");
        databaseRefusesTheOrder(failure);
        doThrow(new PixGatewayException(PixGatewayException.Kind.UNAVAILABLE, "CANCEL_CHARGE", "HTTP 503"))
                .when(gateway)
                .cancelCharge("pay_created");

        assertThatThrownBy(() -> service.create(customerId, "c@example.com", new CreateOrderRequest(productId, null)))
                .isSameAs(failure);

        verify(gateway).cancelCharge("pay_created");
    }
}
