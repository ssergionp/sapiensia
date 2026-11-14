package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Nothing sensitive reaches any table: a scan of every table of the schema after a real purchase. */
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=orders", "app.login-limit.ip-max-attempts=1000"})
class OrderCpfNotStoredTest {

    private static final String CPF = "52998224725";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SwitchablePixGateway gateway;

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
        OrderTestSupport.deleteOrdersOf(jdbc, customer.id, productId);
    }

    /** Rows, over every table of the public schema, whose text contains one of the needles. */
    private long rowsContaining(String... needles) {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables"
                        + " where table_schema = 'public' and table_type = 'BASE TABLE'",
                String.class);
        long found = 0;
        for (String table : tables) {
            for (String needle : needles) {
                // t::text shows a bytea column (for example scheduled_tasks.task_data) as hex, so the hex of the
                // UTF-8 bytes is searched too
                Long count = jdbc.queryForObject(
                        "select count(*) from \"" + table + "\" t where t::text like ?"
                                + " or t::text like '%' || encode(convert_to(?, 'UTF8'), 'hex') || '%'",
                        Long.class,
                        "%" + needle + "%",
                        needle);
                found += count;
            }
        }
        return found;
    }

    @Test
    @DisplayName("SEC-033, ADR-0003: shouldStoreTheCpfInNoTableAtAll")
    void shouldStoreTheCpfInNoTableAtAll() throws Exception {
        String id = OrderHttp.json(customer.order(productId, "529.982.247-25"), "$.id");
        assertThat(customer.get("/api/orders/" + id).statusCode()).isEqualTo(200);

        assertThat(gateway.lastCustomerCpf()).isEqualTo(CPF);
        assertThat(OrderTestSupport.countOrdersOf(jdbc, customer.id)).isEqualTo(1);
        assertThat(rowsContaining(CPF, "529.982.247-25")).isZero();
        // positive control: the scan does see what is stored (the order id)
        assertThat(rowsContaining(id)).isPositive();
    }

    @Test
    @DisplayName("ADR-0003: shouldStoreThePixCodeInNoTableAtAll")
    void shouldStoreThePixCodeInNoTableAtAll() throws Exception {
        String id = OrderHttp.json(customer.order(productId, CPF), "$.id");
        String code = OrderHttp.json(customer.get("/api/orders/" + id), "$.pixCopyPaste");

        assertThat(code).isNotBlank();
        assertThat(rowsContaining(code)).isZero();
        assertThat(rowsContaining(id)).isPositive();
    }
}
