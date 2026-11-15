package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.product.LocalDownloadFixtures;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorageConfig;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
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

/**
 * FR-025: repurchase after a refund, through the real HTTP API. Like LibraryDownloadTest it creates its OWN Customer
 * and Product and never deletes them: download_records is insert-only and pins both.
 */
@Import({TestcontainersConfiguration.class, SwitchableProductFileStorageConfig.class})
// Same annotations and properties as LibraryDownloadTest, so the cached context is reused.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=library-download",
            "app.login-limit.ip-max-attempts=1000",
            "app.storage.file.dir=target/test-storage-library-download"
        })
class RefundRepurchaseTest {

    private static final byte[] PDF = "%PDF-1.7 refund repurchase content".getBytes(StandardCharsets.UTF_8);

    private static final String CPF = "52998224725";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    ApplicationContext context;

    @Autowired
    Orders orders;

    @Autowired
    Accesses accesses;

    private OrderHttp.Customer customer;
    private UUID product;

    @BeforeEach
    void setUp() throws Exception {
        customer = OrderHttp.newCustomer(port, jdbc, encoder);
        product = OrderTestSupport.insertPublishedProduct(jdbc, "Guia de Contratos", 2990);
        LocalDownloadFixtures.storeProductFile(context, jdbc, product, PDF);
        jdbc.update(
                "insert into product_covers (product_id, object_key, media_type, size_bytes, width, height, version)"
                        + " values (?, ?, 'image/png', 100, 10, 10, 'abcdef0123456789')",
                product,
                "covers/" + product + "/" + UUID.randomUUID() + ".png");
    }

    private UUID payAndGrant(UUID orderId) {
        Instant now = Instant.now();
        assertThat(orders.markPaid(orderId, now)).isTrue();
        assertThat(accesses.grant(customer.id, product, orderId, now)).isTrue();
        return orderId;
    }

    private HttpResponse<String> download() throws Exception {
        return customer.post("/api/library/" + product + "/download", "");
    }

    private HttpResponse<String> cover() throws Exception {
        return customer.get("/api/library/" + product + "/cover");
    }

    @Test
    @DisplayName("FR-025: recompra depois do estorno")
    void shouldAllowRepurchaseAfterARefund() throws Exception {
        UUID first = UUID.fromString(OrderHttp.json(customer.order(product, CPF), "$.id"));
        payAndGrant(first);
        assertThat(download().statusCode()).isEqualTo(200);

        assertThat(orders.markRefunded(first, Instant.now())).isTrue();
        assertThat(accesses.cancelByOrder(first, Instant.now())).isTrue();

        assertThat(OrderHttp.json(customer.get("/api/library"), "$.items.length()"))
                .isEqualTo("0");
        assertThat(cover().statusCode()).isEqualTo(404);
        assertThat(download().statusCode()).isEqualTo(404);
        HttpResponse<String> again = customer.order(product, CPF);
        assertThat(again.statusCode()).isEqualTo(201);

        UUID second = payAndGrant(UUID.fromString(OrderHttp.json(again, "$.id")));

        HttpResponse<String> library = customer.get("/api/library");
        assertThat(OrderHttp.json(library, "$.items.length()")).isEqualTo("1");
        assertThat(OrderHttp.json(library, "$.items[0].productId")).isEqualTo(product.toString());
        HttpResponse<String> link = download();
        assertThat(link.statusCode()).isEqualTo(200);
        assertThat(customer.get(OrderHttp.json(link, "$.url")).body())
                .isEqualTo(new String(PDF, StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject(
                        "select cancelled_at is not null from accesses where order_id = ?", Boolean.class, first))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select cancelled_at is null from accesses where order_id = ?", Boolean.class, second))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from accesses where customer_id = ? and product_id = ?",
                        Integer.class,
                        customer.id,
                        product))
                .isEqualTo(2);
    }
}
