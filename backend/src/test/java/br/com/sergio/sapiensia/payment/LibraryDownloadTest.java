package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.product.LocalDownloadFixtures;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorage;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorageConfig;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * FR-034, SEC-011, ADR-0007: POST /api/library/{productId}/download issues the 60-second link and writes the Download
 * record.
 *
 * <p>Test data: download_records is insert-only and pins its Customer and Product (foreign keys), and many other test
 * classes run {@code delete from products}. So this class creates its OWN Customers and Products, runs in an isolated
 * context, and never deletes them or their records: a cleanup would hit the append-only guard.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import({TestcontainersConfiguration.class, SwitchableProductFileStorageConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=library-download",
            "app.login-limit.ip-max-attempts=1000",
            "app.storage.file.dir=target/test-storage-library-download"
        })
class LibraryDownloadTest {

    private static final byte[] PDF = "%PDF-1.7 library download content".getBytes(StandardCharsets.UTF_8);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    ApplicationContext context;

    @Autowired
    Accesses accesses;

    @Autowired
    SwitchableProductFileStorage storage;

    private static final List<String> LOGGERS = List.of(
            "org.springframework.web",
            "org.springframework.security",
            "org.springframework.web.client",
            "org.springframework.http");

    private final Map<String, LogLevel> savedLevels = new LinkedHashMap<>();
    private LoggingSystem loggingSystem;
    private OrderHttp.Customer customer;
    private OrderHttp.Customer other;
    private UUID product;
    private Timestamp uploadedAt;

    @BeforeEach
    void setUp() throws Exception {
        // The log assertions must not depend on which test class ran before: force the chatty levels, restore after.
        loggingSystem = LoggingSystem.get(getClass().getClassLoader());
        for (String name : LOGGERS) {
            savedLevels.put(name, loggingSystem.getLoggerConfiguration(name).getConfiguredLevel());
        }
        loggingSystem.setLogLevel("org.springframework.web", LogLevel.DEBUG);
        loggingSystem.setLogLevel("org.springframework.security", LogLevel.TRACE);
        loggingSystem.setLogLevel("org.springframework.web.client", LogLevel.DEBUG);
        loggingSystem.setLogLevel("org.springframework.http", LogLevel.DEBUG);
        customer = OrderHttp.newCustomer(port, jdbc, encoder);
        other = OrderHttp.newCustomer(port, jdbc, encoder);
        product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia de Contratos", 2990);
        uploadedAt = Timestamp.from(LocalDownloadFixtures.storeProductFile(context, jdbc, product, PDF));
    }

    @AfterEach
    void resetStorage() {
        savedLevels.forEach(loggingSystem::setLogLevel); // null restores inheritance
        savedLevels.clear();
        // Only the failure switch: the Customers, Products and Download records stay (insert-only guard).
        storage.failSigns(false);
    }

    private void grant(OrderHttp.Customer buyer, UUID productId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at, paid_at)"
                        + " values (?, ?, ?, 1000, 'PAID', ?, now(), now() + interval '30 minutes', now())",
                orderId,
                buyer.id,
                productId,
                "pay_" + UUID.randomUUID());
        jdbc.update(
                "insert into accesses (customer_id, product_id, order_id, granted_at) values (?, ?, ?, now())",
                buyer.id,
                productId,
                orderId);
    }

    private long records(OrderHttp.Customer who, UUID productId) {
        return jdbc.queryForObject(
                "select count(*) from download_records where customer_id = ? and product_id = ?",
                Long.class,
                who.id,
                productId);
    }

    private HttpResponse<String> download(OrderHttp.Customer who, UUID productId) throws Exception {
        return who.post("/api/library/" + productId + "/download", "");
    }

    private void assertEmpty404(HttpResponse<String> answer) {
        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.body()).isEmpty();
        assertThat(answer.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    @Test
    @DisplayName("FR-034: o dono com Acesso ativo recebe um link e o link entrega o Arquivo")
    void shouldIssueALinkThatDeliversTheFile() throws Exception {
        grant(customer, product);

        HttpResponse<String> answer = download(customer, product);

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        String url = OrderHttp.json(answer, "$.url");
        assertThat(url).startsWith("/api/local-downloads/");
        HttpResponse<String> file = customer.get(url);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(new String(PDF, StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject(
                        "select count(*) from download_records where customer_id = ? and product_id = ?"
                                + " and file_uploaded_at = ?",
                        Long.class,
                        customer.id,
                        product,
                        uploadedAt))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FR-034: cada clique emite um link novo")
    void shouldIssueANewLinkOnEveryClick() throws Exception {
        grant(customer, product);

        String first = OrderHttp.json(download(customer, product), "$.url");
        String second = OrderHttp.json(download(customer, product), "$.url");

        assertThat(first).isNotEqualTo(second);
        assertThat(records(customer, product)).isEqualTo(2);
    }

    @Test
    @DisplayName("ADR-0007: Produto despublicado continua baixável")
    void shouldKeepAnUnpublishedProductDownloadable() throws Exception {
        grant(customer, product);
        jdbc.update("update products set status = 'UNPUBLISHED' where id = ?", product);

        assertThat(download(customer, product).statusCode()).isEqualTo(200);
        assertThat(records(customer, product)).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-011: outro Cliente recebe 404 e nenhum Registro é gravado")
    void shouldAnswer404ToAnotherCustomer() throws Exception {
        grant(customer, product);

        assertEmpty404(download(other, product));
        assertThat(records(other, product)).isZero();
    }

    @Test
    @DisplayName("FR-025: Acesso suspenso não baixa")
    void shouldNotDownloadWithASuspendedAccess() throws Exception {
        grant(customer, product);
        UUID orderId = jdbc.queryForObject(
                "select order_id from accesses where customer_id = ? and product_id = ?",
                UUID.class,
                customer.id,
                product);
        assertThat(accesses.suspendByOrder(orderId, java.time.Instant.now())).isTrue();

        assertEmpty404(download(customer, product));
        assertThat(records(customer, product)).isZero();
    }

    @Test
    @DisplayName("SEC-011: Cliente sem Acesso recebe 404")
    void shouldAnswer404WithoutAccess() throws Exception {
        assertEmpty404(download(customer, product));
        assertThat(records(customer, product)).isZero();
    }

    @Test
    @DisplayName("SEC-011: Acesso cancelado recebe 404")
    void shouldAnswer404ForACancelledAccess() throws Exception {
        grant(customer, product);
        jdbc.update(
                "update accesses set cancelled_at = now() where customer_id = ? and product_id = ?",
                customer.id,
                product);

        assertEmpty404(download(customer, product));
        assertThat(records(customer, product)).isZero();
    }

    @Test
    @DisplayName("SEC-011: Produto inexistente e Produto sem Arquivo recebem 404")
    void shouldAnswer404ForAMissingProductAndAProductWithoutFile() throws Exception {
        UUID noFile = OrderTestSupport.insertPublishedProduct(jdbc, "Sem arquivo", 1000);
        grant(customer, noFile);

        assertEmpty404(download(customer, UUID.randomUUID()));
        assertEmpty404(download(customer, noFile));
        assertThat(records(customer, noFile)).isZero();
    }

    @Test
    @DisplayName("SEC-011: o link de um Cliente não muda o que outro pode pedir")
    void shouldNotLetAnotherCustomerRequestWithAKnownProductId() throws Exception {
        grant(customer, product);
        assertThat(download(customer, product).statusCode()).isEqualTo(200);

        assertEmpty404(download(other, product));
        assertThat(records(other, product)).isZero();
    }

    @Test
    @DisplayName("FR-034 ADR-0007: depois da troca, o clique seguinte entrega a versão nova")
    void shouldDeliverTheNewVersionOnTheNextClickAfterAReplacement() throws Exception {
        grant(customer, product);
        assertThat(download(customer, product).statusCode()).isEqualTo(200);
        Timestamp newUploadedAt = Timestamp.from(LocalDownloadFixtures.replaceProductFile(context, jdbc, product, 3));

        String url = OrderHttp.json(download(customer, product), "$.url");

        HttpResponse<String> file = customer.get(url);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isNotEqualTo(new String(PDF, StandardCharsets.UTF_8));
        assertThat(newUploadedAt).isNotEqualTo(uploadedAt);
        assertThat(downloadedVersions(customer, product)).containsExactly(uploadedAt, newUploadedAt);
    }

    @Test
    @DisplayName("FR-034: o link emitido antes da troca ainda entrega a versão anterior dentro dos 60 segundos")
    void shouldKeepAPreReplacementLinkWorkingWithinItsMinute() throws Exception {
        grant(customer, product);
        String oldLink = OrderHttp.json(download(customer, product), "$.url");

        LocalDownloadFixtures.replaceProductFile(context, jdbc, product, 3);

        HttpResponse<String> file = customer.get(oldLink);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(new String(PDF, StandardCharsets.UTF_8));
        assertThat(downloadedVersions(customer, product)).containsExactly(uploadedAt);
    }

    private java.util.List<Timestamp> downloadedVersions(OrderHttp.Customer who, UUID productId) {
        return jdbc.queryForList(
                "select file_uploaded_at from download_records where customer_id = ? and product_id = ?"
                        + " order by downloaded_at",
                Timestamp.class,
                who.id,
                productId);
    }

    @Test
    @DisplayName("FR-034: id malformado recebe 400")
    void shouldAnswer400ForAMalformedId() throws Exception {
        assertThat(customer.post("/api/library/nao-e-uuid/download", "").statusCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("FR-034: Storage fora dá 503 sem corpo e nenhum Registro; os logs não vazam o link")
    void shouldAnswer503WhenTheStorageIsDown(CapturedOutput output) throws Exception {
        grant(customer, product);
        int successStart = output.getAll().length();
        HttpResponse<String> issued = download(customer, product);
        assertThat(issued.statusCode()).isEqualTo(200);
        String issuedUrl = OrderHttp.json(issued, "$.url");
        assertThat(output.getAll().substring(successStart))
                .doesNotContain(issuedUrl)
                .doesNotContain("/api/local-downloads/")
                .doesNotContain(issuedUrl.substring(issuedUrl.lastIndexOf('/') + 1)) // the link token itself
                .doesNotContain("token=")
                .doesNotContain("products/")
                .doesNotContain(".pdf")
                .doesNotContain(customer.email);
        storage.failSigns(true);
        int logStart = output.getAll().length(); // only what the failing request logs: startup noise has "tokens"

        HttpResponse<String> answer = download(customer, product);

        assertThat(answer.statusCode()).isEqualTo(503);
        assertThat(answer.body()).isEmpty();
        assertThat(records(customer, product)).isEqualTo(1);
        assertThat(output.getAll().substring(logStart))
                .contains("Library download unavailable (product " + product + ", "
                        + "br.com.sergio.sapiensia.product.ProductFileStorageException)")
                .doesNotContain(issuedUrl)
                .doesNotContain(issuedUrl.substring(issuedUrl.lastIndexOf('/') + 1))
                .doesNotContain("/api/local-downloads/")
                .doesNotContain("token=")
                .doesNotContain("products/")
                .doesNotContain(".pdf")
                .doesNotContain(customer.email)
                .doesNotContainIgnoringCase("cpf");
    }

    @Test
    @DisplayName("SEC-015: o 11º pedido do dia responde 429 DOWNLOAD_LIMIT sem link")
    void shouldAnswer429OnTheEleventhDownloadOfTheDay(CapturedOutput output) throws Exception {
        grant(customer, product);
        for (int i = 0; i < 10; i++) {
            assertThat(download(customer, product).statusCode()).isEqualTo(200);
        }
        int logStart = output.getAll().length();

        HttpResponse<String> answer = download(customer, product);

        assertThat(answer.statusCode()).isEqualTo(429);
        assertThat(OrderHttp.json(answer, "$.errors.download")).isEqualTo("DOWNLOAD_LIMIT");
        assertThat(answer.body()).doesNotContain("url").doesNotContain("local-downloads");
        assertThat(answer.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(records(customer, product)).isEqualTo(10);
        assertThat(output.getAll().substring(logStart)).doesNotContain("ERROR");
    }

    @Test
    @DisplayName("FR-034: objeto ausente no Storage dá 503 sem Registro")
    void shouldAnswer503WhenTheObjectIsMissing() throws Exception {
        UUID lost = OrderTestSupport.insertPublishedProduct(jdbc, "Perdido", 1000);
        LocalDownloadFixtures.insertMissingObjectRow(jdbc, lost);
        grant(customer, lost);

        HttpResponse<String> answer = download(customer, lost);

        assertThat(answer.statusCode()).isEqualTo(503);
        assertThat(answer.body()).isEmpty();
        assertThat(records(customer, lost)).isZero();
    }
}
