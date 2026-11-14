package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.product.LocalDownloadFixtures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** FR-034, SEC-010: the local 60-second download link (disk storage) is served to the logged-in Customer only. */
@Import({TestcontainersConfiguration.class, OrderExpirationTest.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=local-download",
            "app.login-limit.ip-max-attempts=1000",
            "db-scheduler.polling-interval=PT1H"
        })
class LocalDownloadTest {

    private static final byte[] PDF = "%PDF-1.7 local download content".getBytes(StandardCharsets.UTF_8);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    OrderExpirationTest.MutableClock clock;

    @Autowired
    ApplicationContext context;

    private OrderHttp.Customer customer;

    @BeforeEach
    void setUp() throws Exception {
        customer = OrderHttp.newCustomer(port, jdbc, encoder);
    }

    @Test
    @DisplayName("FR-034, SEC-010: o Cliente logado baixa o arquivo como anexo, sem cache")
    void shouldServeTheFileAsAnAttachment() throws Exception {
        String link = LocalDownloadFixtures.storeAndLink(context, PDF, "guia-de-contratos.pdf");

        HttpResponse<String> response = customer.get(link);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(new String(PDF, StandardCharsets.UTF_8));
        assertThat(response.headers().firstValue("Content-Type")).contains("application/pdf");
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow())
                .startsWith("attachment")
                .contains("guia-de-contratos.pdf");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    @Test
    @DisplayName("FR-034: o link vence em 60 segundos")
    void shouldExpireAfterSixtySeconds() throws Exception {
        String link = LocalDownloadFixtures.storeAndLink(context, PDF, "a.pdf");
        clock.advance(Duration.ofSeconds(59));
        assertThat(customer.get(link).statusCode()).isEqualTo(200);

        clock.advance(Duration.ofSeconds(2));
        HttpResponse<String> expired = customer.get(link);

        assertThat(expired.statusCode()).isEqualTo(404);
        assertThat(expired.body()).isEmpty();
    }

    @Test
    @DisplayName("SEC-010: token inventado dá 404 sem corpo")
    void shouldAnswer404ForAnInventedToken() throws Exception {
        HttpResponse<String> response = customer.get("/api/local-downloads/" + "x".repeat(43));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("SEC-010: anônimo com link válido recebe 401")
    void shouldRefuseAnAnonymousRequest() throws Exception {
        String link = LocalDownloadFixtures.storeAndLink(context, PDF, "a.pdf");

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + link))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
