package br.com.sergio.sapiensia.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.payment.SwitchablePixGateway;
import br.com.sergio.sapiensia.payment.SwitchablePixGatewayConfig;
import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * SEC-014 and SEC-018: every limited route refuses the request after its limit with 429 RATE_LIMITED and Retry-After.
 * One context with small capacities (and no IP floor); every test uses its own Customers and e-mails, and the buckets
 * of one operation are used by one test only, except the ones whose capacity is large enough for all of them.
 */
@Import({TestcontainersConfiguration.class, SwitchablePixGatewayConfig.class})
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=rate-limit-routes",
            "app.login-limit.ip-max-attempts=1000",
            "app.rate-limit.ip-capacity-floor=0",
            "app.rate-limit.order-create.account-capacity=2",
            "app.rate-limit.order-create.ip-capacity=100",
            "app.rate-limit.order-read.account-capacity=2",
            "app.rate-limit.order-read.ip-capacity=100",
            "app.rate-limit.download.account-capacity=2",
            "app.rate-limit.download.ip-capacity=100",
            "app.rate-limit.cover.ip-capacity=3",
            "app.rate-limit.sign-up.account-capacity=2",
            "app.rate-limit.sign-up.ip-capacity=6",
            "app.rate-limit.password-reset-request.account-capacity=2",
            "app.rate-limit.password-reset-request.ip-capacity=6",
            "app.rate-limit.password-reset-confirm.ip-capacity=2",
            "app.rate-limit.email-confirmation.ip-capacity=2",
            "app.rate-limit.admin-password-setup.ip-capacity=2"
        })
class RateLimitRoutesTest {

    private static final String PASSWORD = "senhalongaok"; // gitleaks:allow
    private static final String CPF = "52998224725";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    SwitchablePixGateway gateway;

    @Autowired
    RecordingEmailSender sender;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    /** A real HTTP client with its own cookie jar and the CSRF token of the SPA; logged in or anonymous. */
    private final class Client {

        private final CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http =
                HttpClient.newBuilder().cookieHandler(jar).build();
        UUID id;
        String email;

        private URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }

        private String xsrf() {
            return jar.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                    .map(HttpCookie::getValue)
                    .reduce((first, last) -> last)
                    .orElseThrow();
        }

        Client visit() throws Exception {
            http.send(HttpRequest.newBuilder(uri("/cadastro")).build(), HttpResponse.BodyHandlers.ofString());
            return this;
        }

        Client logIn() throws Exception {
            email = "cliente-" + UUID.randomUUID() + "@example.com";
            id = jdbc.queryForObject(
                    "insert into customers (email, password_hash, email_confirmed_at) values (?, ?, now())"
                            + " returning id",
                    UUID.class,
                    email,
                    encoder.encode(PASSWORD));
            visit();
            HttpResponse<String> login =
                    post("/api/sessions", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
            assertThat(login.statusCode()).isEqualTo(204);
            return this;
        }

        HttpResponse<String> post(String path, String json) throws Exception {
            return http.send(
                    HttpRequest.newBuilder(uri(path))
                            .timeout(Duration.ofSeconds(60))
                            .header("Content-Type", "application/json")
                            .header("X-XSRF-TOKEN", xsrf())
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(
                    HttpRequest.newBuilder(uri(path))
                            .timeout(Duration.ofSeconds(60))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> head(String path) throws Exception {
            return http.send(
                    HttpRequest.newBuilder(uri(path))
                            .timeout(Duration.ofSeconds(60))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> order(UUID productId) throws Exception {
            return post("/api/orders", "{\"productId\":\"" + productId + "\",\"cpf\":\"" + CPF + "\"}");
        }
    }

    private UUID newProduct() {
        return jdbc.queryForObject(
                "insert into products (title, description, category, price_cents, status, first_published_at)"
                        + " values ('Guia de Contratos', 'd', 'LAW', 2990, 'PUBLISHED', now()) returning id",
                UUID.class);
    }

    private static void assertRateLimited(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        assertThat(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()))
                .isGreaterThanOrEqualTo(1);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("RATE_LIMITED");
        assertThat((String) JsonPath.read(response.body(), "$.title")).isEqualTo("Too many requests");
    }

    private static void assertNotRateLimited(HttpResponse<String> response) {
        assertThat(response.statusCode()).isNotEqualTo(429);
    }

    private static String registration(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"Zq9-" + UUID.randomUUID() + "\"}";
    }

    @Test
    @DisplayName("SEC-014: criar Pedido além do limite do Cliente recebe 429 com Retry-After e não chama o Asaas")
    void shouldRefuseOrderCreationBeyondTheCustomerLimitWithoutCallingTheProvider() throws Exception {
        Client customer = new Client().logIn();
        UUID product = newProduct();

        assertThat(customer.order(product).statusCode()).isEqualTo(201);
        assertThat(customer.order(product).statusCode()).isEqualTo(200);
        long charges = gateway.count("CREATE_CHARGE");
        long calls = gateway.calls().size();

        assertRateLimited(customer.order(product));

        assertThat(gateway.count("CREATE_CHARGE")).isEqualTo(charges);
        assertThat(gateway.calls()).hasSize((int) calls);
        assertNotRateLimited(new Client().logIn().order(product));
    }

    @Test
    @DisplayName("SEC-014: abrir o Pedido além do limite recebe 429 (inclui id de outro Cliente: conta antes do 404)")
    void shouldRefuseOrderReadsBeyondTheLimitEvenForAnotherCustomersIdOrAMalformedOne() throws Exception {
        Client owner = new Client().logIn();
        String orderId = JsonPath.read(owner.order(newProduct()).body(), "$.id");

        assertThat(owner.get("/api/orders/" + orderId).statusCode()).isEqualTo(200);
        assertThat(owner.get("/api/orders/" + orderId).statusCode()).isEqualTo(200);
        assertRateLimited(owner.get("/api/orders/" + orderId));

        Client other = new Client().logIn();
        assertThat(other.get("/api/orders/" + orderId).statusCode()).isEqualTo(404);
        assertThat(other.get("/api/orders/not-a-uuid").statusCode()).isEqualTo(400);
        assertRateLimited(other.get("/api/orders/" + orderId));
    }

    @Test
    @DisplayName("SEC-014: GET /api/orders/{id}/status não é limitado")
    void shouldNotLimitTheOrderStatusRoute() throws Exception {
        Client customer = new Client().logIn();
        String orderId = JsonPath.read(customer.order(newProduct()).body(), "$.id");

        for (int i = 0; i < 6; i++) {
            assertThat(customer.get("/api/orders/" + orderId + "/status").statusCode())
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("SEC-014: Download além do limite por Cliente recebe 429 RATE_LIMITED, não DOWNLOAD_LIMIT")
    void shouldRefuseDownloadsBeyondTheCustomerLimitWithTheGenericCode() throws Exception {
        Client customer = new Client().logIn();
        String path = "/api/library/" + UUID.randomUUID() + "/download";

        assertThat(customer.post(path, "{}").statusCode()).isEqualTo(404);
        assertThat(customer.post(path, "{}").statusCode()).isEqualTo(404);

        HttpResponse<String> refused = customer.post(path, "{}");
        assertRateLimited(refused);
        assertThat(refused.body()).doesNotContain("DOWNLOAD_LIMIT");
        assertNotRateLimited(new Client().logIn().post(path, "{}"));
    }

    @Test
    @DisplayName("SEC-014: capa pública além do limite do IP recebe 429")
    void shouldRefuseCoversBeyondTheIpLimitOnBothCoverRoutes() throws Exception {
        Client visitor = new Client().visit();
        Client customer = new Client().logIn();

        assertThat(visitor.get("/api/products/" + UUID.randomUUID() + "/cover").statusCode())
                .isEqualTo(404);
        assertThat(visitor.get("/api/products/" + UUID.randomUUID() + "/cover").statusCode())
                .isEqualTo(404);
        assertThat(customer.get("/api/library/" + UUID.randomUUID() + "/cover").statusCode())
                .isEqualTo(404);
        // Security allows GET only on the cover routes, so a HEAD never reaches the limit (nor the controller)
        assertThat(visitor.head("/api/products/" + UUID.randomUUID() + "/cover").statusCode())
                .isEqualTo(401);

        assertRateLimited(visitor.get("/api/products/" + UUID.randomUUID() + "/cover"));
        assertRateLimited(customer.get("/api/library/" + UUID.randomUUID() + "/cover"));
    }

    @Test
    @DisplayName("SEC-014: cadastro além do limite por e-mail e por IP")
    void shouldRefuseSignUpsBeyondTheEmailLimitAndTheIpLimit(CapturedOutput output) throws Exception {
        Client visitor = new Client().visit();
        String email = "cadastro-" + UUID.randomUUID() + "@example.com";

        assertThat(visitor.post("/api/customers", registration(email)).statusCode())
                .isEqualTo(202);
        assertThat(visitor.post("/api/customers", registration(email)).statusCode())
                .isEqualTo(202);
        assertRateLimited(visitor.post("/api/customers", registration(email)));
        // the same e-mail with other letter case and spaces is the same account
        assertRateLimited(visitor.post("/api/customers", registration(" " + email.toUpperCase() + " ")));

        // the IP bucket has 6: two used by the first e-mail, the refused ones gave theirs back
        for (int i = 0; i < 2; i++) {
            assertThat(visitor.post("/api/customers", registration("a-" + UUID.randomUUID() + "@example.com"))
                            .statusCode())
                    .isEqualTo(202);
            assertThat(visitor.post("/api/customers", registration("b-" + UUID.randomUUID() + "@example.com"))
                            .statusCode())
                    .isEqualTo(202);
        }
        assertRateLimited(visitor.post("/api/customers", registration("c-" + UUID.randomUUID() + "@example.com")));

        assertThat(output.getAll()).doesNotContain(email).doesNotContain(RequestRateLimiter.emailKey(email));
    }

    @Test
    @DisplayName("SEC-014: pedido de nova senha além do limite por e-mail e por IP não envia e-mail")
    void shouldRefusePasswordResetRequestsBeyondTheLimitsWithoutSendingEmail() throws Exception {
        Client visitor = new Client().visit();
        Client owner = new Client().logIn();
        String body = "{\"email\":\"" + owner.email + "\"}";

        assertThat(visitor.post("/api/customers/password-resets", body).statusCode())
                .isEqualTo(202);
        assertThat(visitor.post("/api/customers/password-resets", body).statusCode())
                .isEqualTo(202);
        assertThat(sender.awaitMessageTo(owner.email, Duration.ofSeconds(15))).isPresent();
        assertThat(sender.awaitMessageTo(owner.email, Duration.ofSeconds(15))).isPresent();
        int sent = sender.messagesTo(owner.email).size();

        assertRateLimited(visitor.post("/api/customers/password-resets", body));
        assertThat(sender.awaitMessageTo(owner.email, Duration.ofSeconds(2))).isEmpty();
        assertThat(sender.messagesTo(owner.email)).hasSize(sent);

        for (int i = 0; i < 4; i++) {
            assertThat(visitor.post(
                                    "/api/customers/password-resets",
                                    "{\"email\":\"x-" + UUID.randomUUID() + "@example.com\"}")
                            .statusCode())
                    .isEqualTo(202);
        }
        assertRateLimited(visitor.post(
                "/api/customers/password-resets", "{\"email\":\"y-" + UUID.randomUUID() + "@example.com\"}"));
    }

    @Test
    @DisplayName("SEC-014: troca de senha pelo link além do limite do IP")
    void shouldRefusePasswordResetConfirmationsBeyondTheIpLimit() throws Exception {
        Client visitor = new Client().visit();
        String body = "{\"token\":\"invalid\",\"newPassword\":\"Zq9-" + UUID.randomUUID() + "\"}";

        assertThat(visitor.post("/api/customers/password-resets/confirmation", body)
                        .statusCode())
                .isEqualTo(400);
        assertThat(visitor.post("/api/customers/password-resets/confirmation", body)
                        .statusCode())
                .isEqualTo(400);

        assertRateLimited(visitor.post("/api/customers/password-resets/confirmation", body));
    }

    @Test
    @DisplayName("SEC-014: confirmação de e-mail além do limite do IP")
    void shouldRefuseEmailConfirmationsBeyondTheIpLimit() throws Exception {
        Client visitor = new Client().visit();
        String body = "{\"token\":\"invalid\",\"password\":\"" + PASSWORD + "\"}";

        assertThat(visitor.post("/api/customers/email-confirmations", body).statusCode())
                .isEqualTo(400);
        assertThat(visitor.post("/api/customers/email-confirmations", body).statusCode())
                .isEqualTo(400);

        assertRateLimited(visitor.post("/api/customers/email-confirmations", body));
    }

    @Test
    @DisplayName("SEC-014: definição de senha do Administrador além do limite do IP")
    void shouldRefuseAdministratorPasswordSetupsBeyondTheIpLimit() throws Exception {
        Client visitor = new Client().visit();
        String body = "{\"token\":\"invalid\",\"newPassword\":\"Zq9-" + UUID.randomUUID() + "\"}";

        assertThat(visitor.post("/api/admin/password-setup", body).statusCode()).isEqualTo(400);
        assertThat(visitor.post("/api/admin/password-setup", body).statusCode()).isEqualTo(400);

        assertRateLimited(visitor.post("/api/admin/password-setup", body));
    }

    @Test
    @DisplayName("SEC-018: o 429 não traz e-mail, IP nem detalhe interno e o log do limite também não")
    void shouldNotLeakEmailIpOrDetailInTheRefusalOrItsLog(CapturedOutput output) throws Exception {
        Client customer = new Client().logIn();
        UUID product = newProduct();
        customer.order(product);
        customer.order(product);

        HttpResponse<String> refused = customer.order(product);

        assertRateLimited(refused);
        Map<String, Object> body = JsonPath.read(refused.body(), "$");
        // "instance" is the request path that Spring adds to every ProblemDetail; there is no "detail"
        assertThat(body).containsOnlyKeys("title", "status", "code", "instance").containsEntry("status", 429);
        assertThat(refused.body())
                .doesNotContain(customer.email)
                .doesNotContain("127.0.0.1")
                .doesNotContain("0:0:0:0:0:0:0:1")
                .doesNotContain("::1")
                .doesNotContain(customer.id.toString());
        String log = output.getAll();
        assertThat(log).contains("Request limit reached for ORDER_CREATE");
        for (String line : List.of(log.split("\n"))) {
            if (line.contains("Request limit reached")) {
                assertThat(line)
                        .doesNotContain(customer.email)
                        .doesNotContain("127.0.0.1")
                        .doesNotContain("0:0:0:0:0:0:0:1")
                        .doesNotContain(customer.id.toString())
                        .doesNotContain(RequestRateLimiter.emailKey(customer.email));
            }
        }
    }
}
