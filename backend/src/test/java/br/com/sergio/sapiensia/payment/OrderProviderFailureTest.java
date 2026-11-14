package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** SEC-018: a failing payment provider never leaves an Order behind and never leaks its details. */
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=orders", "app.login-limit.ip-max-attempts=1000"})
class OrderProviderFailureTest {

    private static final String CPF = "52998224725";
    private static final Set<String> PROBLEM_FIELDS = Set.of("type", "title", "status", "detail", "instance", "errors");

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
        jdbc.update("delete from orders");
        jdbc.update("delete from payment_customers");
    }

    private void assertPaymentProblem(HttpResponse<String> answer) {
        assertThat(answer.statusCode()).isEqualTo(502);
        assertThat(OrderHttp.json(answer, "$.errors.payment")).isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE");
        Map<String, Object> body = JsonPath.read(answer.body(), "$");
        assertThat(body.keySet()).isSubsetOf(PROBLEM_FIELDS);
        assertThat(answer.body()).doesNotContain("pay_").doesNotContain(CPF).doesNotContain("Pix gateway");
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "FIND_CUSTOMER,REFUSED",
        "FIND_CUSTOMER,UNAVAILABLE",
        "CREATE_CUSTOMER,REFUSED",
        "CREATE_CUSTOMER,UNAVAILABLE",
        "CREATE_CHARGE,REFUSED",
        "CREATE_CHARGE,UNAVAILABLE"
    })
    @DisplayName("SEC-018: shouldStoreNoOrderWhenTheProviderFails")
    void shouldStoreNoOrderWhenTheProviderFails(String step, PixGatewayException.Kind kind) throws Exception {
        gateway.failAt(step, kind);

        HttpResponse<String> answer = customer.order(productId, CPF);

        assertPaymentProblem(answer);
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("SEC-018: shouldKeepTheOrderWhenThePixCodeCannotBeRead")
    void shouldKeepTheOrderWhenThePixCodeCannotBeRead() throws Exception {
        String id = OrderHttp.json(customer.order(productId, CPF), "$.id");
        gateway.failAt("READ_QR_CODE", PixGatewayException.Kind.UNAVAILABLE);

        assertPaymentProblem(customer.get("/api/orders/" + id));

        assertThat(jdbc.queryForObject("select status from orders where id = ?::uuid", String.class, id))
                .isEqualTo("PENDING");
        gateway.failAt("READ_QR_CODE", null);
        HttpResponse<String> back = customer.get("/api/orders/" + id);
        assertThat(back.statusCode()).isEqualTo(200);
        assertThat(OrderHttp.json(back, "$.pixCopyPaste")).isNotBlank();
    }

    @Test
    @DisplayName("SEC-018: shouldWorkAgainAfterTheProviderRecovers")
    void shouldWorkAgainAfterTheProviderRecovers() throws Exception {
        gateway.failAt("CREATE_CHARGE", PixGatewayException.Kind.UNAVAILABLE);
        assertPaymentProblem(customer.order(productId, CPF));

        gateway.failAt("CREATE_CHARGE", null);

        assertThat(customer.order(productId, CPF).statusCode()).isEqualTo(201);
    }
}
