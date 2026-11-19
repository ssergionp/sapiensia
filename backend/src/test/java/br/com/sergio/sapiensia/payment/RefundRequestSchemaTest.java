package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-025: the refund request table accepts only known reasons, a short non-empty detail, and one request per Order. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "test.isolated-context=refund-request-schema")
class RefundRequestSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    private UUID customerId;
    private UUID productId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        customerId = OrderTestSupport.insertCustomer(jdbc);
        productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia", 1000);
        orderId = UUID.randomUUID();
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at, paid_at)"
                        + " values (?, ?, ?, 1000, 'PAID', ?, now(), now() + interval '30 minutes', now())",
                orderId,
                customerId,
                productId,
                "pay_" + UUID.randomUUID());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from refund_requests where customer_id = ?", customerId);
        OrderTestSupport.deleteOrdersOf(jdbc, customerId, productId);
        jdbc.update("delete from customers where id = ?", customerId);
    }

    private void insert(UUID order, String reason, String details) {
        jdbc.update(
                "insert into refund_requests (id, order_id, customer_id, reason, details, requested_at)"
                        + " values (?, ?, ?, ?, ?, now())",
                UUID.randomUUID(),
                order,
                customerId,
                reason,
                details);
    }

    @Test
    @DisplayName("FR-025: shouldStoreAValidRequest")
    void shouldStoreAValidRequest() {
        insert(orderId, "OTHER", "x".repeat(500));

        assertThat(jdbc.queryForObject("select count(*) from refund_requests where order_id = ?", Long.class, orderId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FR-025: shouldRejectUnknownReason")
    void shouldRejectUnknownReason() {
        assertThatThrownBy(() -> insert(orderId, "BECAUSE", null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("FR-025: shouldRejectDetailsLongerThan500")
    void shouldRejectDetailsLongerThan500() {
        assertThatThrownBy(() -> insert(orderId, "OTHER", "x".repeat(501)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("FR-025: shouldRejectEmptyDetails")
    void shouldRejectEmptyDetails() {
        assertThatThrownBy(() -> insert(orderId, "OTHER", "")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("FR-025: shouldRejectSecondRequestForSameOrder")
    void shouldRejectSecondRequestForSameOrder() {
        insert(orderId, "OTHER", null);

        assertThatThrownBy(() -> insert(orderId, "DEFECTIVE_FILE", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
