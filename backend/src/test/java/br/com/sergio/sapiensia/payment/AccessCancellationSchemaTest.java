package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-025: an Access carries a nullable cancellation timestamp; null means active. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "test.isolated-context=access-cancellation-schema")
class AccessCancellationSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    private UUID customerId;
    private UUID productId;

    @AfterEach
    void cleanUp() {
        OrderTestSupport.deleteOrdersOf(jdbc, customerId, productId);
        jdbc.update("delete from customers where id = ?", customerId);
    }

    @Test
    @DisplayName("FR-025: shouldHaveANullCancelledAtOnANewAccess")
    void shouldHaveANullCancelledAtOnANewAccess() {
        customerId = OrderTestSupport.insertCustomer(jdbc);
        productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia", 1000);
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at, paid_at)"
                        + " values (?, ?, ?, 1000, 'PAID', ?, now(), now() + interval '30 minutes', now())",
                orderId,
                customerId,
                productId,
                "pay_" + UUID.randomUUID());
        jdbc.update(
                "insert into accesses (customer_id, product_id, order_id, granted_at) values (?, ?, ?, now())",
                customerId,
                productId,
                orderId);

        assertThat(jdbc.queryForObject(
                        "select count(*) from information_schema.columns where table_name = 'accesses'"
                                + " and column_name = 'cancelled_at' and is_nullable = 'YES'",
                        Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select cancelled_at is null from accesses where customer_id = ?", Boolean.class, customerId))
                .isTrue();
    }
}
