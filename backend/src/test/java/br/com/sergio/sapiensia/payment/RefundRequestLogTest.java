package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
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

/** Guard: the refund request is logged with the Order id and the reason only, never the comment or the e-mail. */
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
// Same annotations and properties as OrderLogTest, so the cached context (with its TRACE levels) is reused.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=order-log",
            "app.login-limit.ip-max-attempts=1000",
            "db-scheduler.polling-interval=PT1H",
            "logging.level.org.springframework.security=TRACE",
            "logging.level.org.springframework.web=TRACE",
            "logging.level.org.springframework.http=TRACE",
            "logging.level.br.com.sergio.sapiensia=TRACE"
        })
class RefundRequestLogTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    Orders orders;

    @Autowired
    Accesses accesses;

    @Test
    @DisplayName("SEC-036: o log da solicitação tem orderId e reason e não tem o comentário nem o e-mail")
    void shouldLogTheOrderAndTheReasonButNeverTheCommentOrTheEmail(CapturedOutput output) throws Exception {
        OrderHttp.Customer customer = OrderHttp.newCustomer(port, jdbc, encoder);
        UUID productId = OrderTestSupport.insertPublishedProduct(jdbc, "Guia " + UUID.randomUUID(), 2990);
        HttpResponse<String> created = customer.order(productId, "52998224725");
        assertThat(created.statusCode()).isEqualTo(201);
        UUID orderId = UUID.fromString(OrderHttp.json(created, "$.id"));
        Instant now = Instant.now();
        assertThat(orders.markPaid(orderId, now)).isTrue();
        assertThat(accesses.grant(customer.id, productId, orderId, now)).isTrue();
        String marker = "comentario-secreto-" + UUID.randomUUID();
        String path = "/api/orders/" + orderId + "/refund-request";

        // (a) a comment over the limit and (b) an unknown reason: refused bodies must not echo the comment either
        assertThat(customer.post(path, "{\"reason\":\"OTHER\",\"details\":\"" + marker + "x".repeat(500) + "\"}")
                        .statusCode())
                .isEqualTo(400);
        assertThat(customer.post(path, "{\"reason\":\"" + marker + "\",\"details\":\"" + marker + "\"}")
                        .statusCode())
                .isEqualTo(400);
        // (c) the request, then (d) the same request again
        String json = "{\"reason\":\"DEFECTIVE_FILE\",\"details\":\"" + marker + "\"}";
        assertThat(customer.post(path, json).statusCode()).isEqualTo(201);
        assertThat(customer.post(path, json).statusCode()).isEqualTo(200);

        String logs = output.getAll();
        assertThat(logs).doesNotContain(marker).doesNotContain(customer.email);
        // positive control: one line, for the new request only
        String line = "Refund requested: orderId=" + orderId + " reason=DEFECTIVE_FILE";
        assertThat(logs).contains(line);
        assertThat(logs.indexOf(line)).isEqualTo(logs.lastIndexOf(line));
    }
}
