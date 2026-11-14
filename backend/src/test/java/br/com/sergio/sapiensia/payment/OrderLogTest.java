package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Guard: no log line of the order flow may carry the CPF, the Pix copy-and-paste code or a provider answer. */
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=order-log",
            "app.login-limit.ip-max-attempts=1000",
            // the real scheduler polls once an hour; the test runs the expiration itself
            "db-scheduler.polling-interval=PT1H",
            // the noisiest levels that could print a request, a body or a SQL parameter
            "logging.level.org.springframework.security=TRACE",
            "logging.level.org.springframework.web=TRACE",
            "logging.level.org.springframework.http=TRACE",
            "logging.level.br.com.sergio.sapiensia=TRACE"
        })
class OrderLogTest {

    private static final String CPF = "52998224725";
    private static final String MASKED_CPF = "529.982.247-25";
    private static final String INVALID_CPF = "12345678900";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SwitchablePixGateway gateway;

    @Autowired
    OrderExpiration.Expirer expirer;

    @BeforeEach
    void setUp() {
        gateway.reset();
    }

    @AfterEach
    void cleanUp() {
        gateway.reset();
        jdbc.update("delete from orders");
        jdbc.update("delete from payment_customers");
    }

    @Test
    @DisplayName("SEC-033, ADR-0006: shouldNeverLogTheCpfThePixCodeOrTheProviderAnswer")
    void shouldNeverLogTheCpfThePixCodeOrTheProviderAnswer(CapturedOutput output) throws Exception {
        OrderHttp.Customer customer = OrderHttp.newCustomer(port, jdbc, encoder);
        OrderHttp.Customer other = OrderHttp.newCustomer(port, jdbc, encoder);
        UUID guide = OrderTestSupport.insertPublishedProduct(jdbc, "Guia de Contratos", 2990);
        UUID checklist = OrderTestSupport.insertPublishedProduct(jdbc, "Checklist de Prazos", 1990);

        // (a) an Order with the CPF masked, (b) the same request again
        HttpResponse<String> created = customer.order(guide, MASKED_CPF);
        assertThat(created.statusCode()).isEqualTo(201);
        String orderId = OrderHttp.json(created, "$.id");
        String chargeId = gateway.lastChargeId();
        assertThat(customer.order(guide, MASKED_CPF).statusCode()).isEqualTo(200);

        // (c) an invalid CPF, from a Customer with no payment customer yet
        assertThat(other.order(guide, INVALID_CPF).statusCode()).isEqualTo(400);

        // (d) the provider is unavailable while the charge is created
        gateway.failAt("CREATE_CHARGE", PixGatewayException.Kind.UNAVAILABLE);
        assertThat(customer.order(checklist, MASKED_CPF).statusCode()).isEqualTo(502);
        gateway.failAt("CREATE_CHARGE", null);

        // (e) the Order is read twice
        String pixCode = null;
        for (int read = 0; read < 2; read++) {
            HttpResponse<String> answer = customer.get("/api/orders/" + orderId);
            assertThat(answer.statusCode()).isEqualTo(200);
            pixCode = OrderHttp.json(answer, "$.pixCopyPaste");
        }
        assertThat(pixCode).isNotBlank();

        // (f) the expiration runs
        jdbc.update("update orders set expires_at = now() - interval '1 minute'");
        assertThat(expirer.run().expired()).isEqualTo(1);

        // (g) the CPF as a JSON number: a body that cannot be read, which must not echo the value
        assertThat(customer.post("/api/orders", "{\"productId\":\"" + checklist + "\",\"cpf\":" + CPF + "}")
                        .statusCode())
                .isEqualTo(400);

        String logs = output.getAll();
        assertThat(logs).as("the flow logged something").isNotBlank();
        assertThat(logs)
                .doesNotContain(CPF)
                .doesNotContain(MASKED_CPF)
                .doesNotContain(INVALID_CPF)
                .doesNotContain(pixCode)
                .doesNotContain(customer.email)
                .doesNotContain(other.email);
        // positive control: the flow did log, so the absences above mean something
        assertThat(logs)
                .contains("Order created: orderId=" + orderId + " chargeId=" + chargeId)
                .contains("Pending order reused: orderId=" + orderId)
                .contains("step=CREATE_CHARGE");
    }
}
