package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * FR-034: the service never returns a link without a Download record, and a Storage failure writes no record.
 *
 * <p>Test data: download_records is insert-only and pins its Customer and Product, and other classes run {@code delete
 * from products}. So this class creates its OWN Customers and Products, runs in an isolated context, and never deletes
 * them or their records (a cleanup would hit the append-only guard).
 */
@Import({TestcontainersConfiguration.class, SwitchableProductFileStorageConfig.class})
@SpringBootTest(
        properties = {
            "test.isolated-context=product-download-service",
            "app.storage.file.dir=target/test-storage-download-service"
        })
class ProductDownloadServiceTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ProductDownloadService service;

    @Autowired
    SwitchableProductFileStorage storage;

    @Autowired
    ProductDownloads downloads;

    @Autowired
    Clock clock;

    @Autowired
    ApplicationContext context;

    @Autowired
    PlatformTransactionManager transactionManager;

    private UUID customer;
    private UUID product;

    @BeforeEach
    void setUp() throws Exception {
        customer = jdbc.queryForObject(
                "insert into customers (email, password_hash, email_confirmed_at)"
                        + " values (?, 'x', now()) returning id",
                UUID.class,
                "cliente-" + UUID.randomUUID() + "@example.com");
        product = jdbc.queryForObject(
                "insert into products (title, description, category, price_cents, status, first_published_at)"
                        + " values ('Guia de Contratos', 'd', 'LAW', 1000, 'PUBLISHED', now()) returning id",
                UUID.class);
        UUID order = UUID.randomUUID();
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at, paid_at)"
                        + " values (?, ?, ?, 1000, 'PAID', ?, now(), now() + interval '30 minutes', now())",
                order,
                customer,
                product,
                "pay_" + UUID.randomUUID());
        jdbc.update(
                "insert into accesses (customer_id, product_id, order_id, granted_at) values (?, ?, ?, now())",
                customer,
                product,
                order);
    }

    @AfterEach
    void resetStorage() {
        storage.failSigns(false);
    }

    private long records() {
        return jdbc.queryForObject("select count(*) from download_records where customer_id = ?", Long.class, customer);
    }

    @Test
    @DisplayName("FR-034: com o Storage no ar o link sai e o Registro é gravado")
    void shouldIssueTheLinkAndRecord() throws Exception {
        LocalDownloadFixtures.storeProductFile(context, jdbc, product, "%PDF-1.7 x".getBytes());

        assertThat(service.issue(customer, product)).get().asString().startsWith("/api/local-downloads/");
        assertThat(records()).isEqualTo(1);
        assertThat(ProductDownloadService.LINK_TTL.toSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("FR-034: Storage fora não grava Registro")
    void shouldWriteNoRecordWhenTheStorageIsDown() throws Exception {
        LocalDownloadFixtures.storeProductFile(context, jdbc, product, "%PDF-1.7 x".getBytes());
        storage.failSigns(true);

        assertThatThrownBy(() -> service.issue(customer, product)).isInstanceOf(ProductFileStorageException.class);

        assertThat(records()).isZero();
    }

    @Test
    @DisplayName("FR-034: objeto ausente no Storage lança exceção e não grava Registro")
    void shouldWriteNoRecordWhenTheObjectIsMissing() {
        LocalDownloadFixtures.insertMissingObjectRow(jdbc, product);

        assertThatThrownBy(() -> service.issue(customer, product))
                .isInstanceOf(ProductFileStorageException.class)
                .hasMessage("Storage object is missing");

        assertThat(records()).isZero();
    }

    @Test
    @DisplayName("FR-034: falha na gravação do Registro lança exceção e nenhuma URL é devolvida")
    void shouldReturnNoUrlWhenTheRecordFails() throws Exception {
        LocalDownloadFixtures.storeProductFile(context, jdbc, product, "%PDF-1.7 x".getBytes());
        ProductDownloads failing = new ProductDownloads(jdbcClient) {
            @Override
            void record(UUID customerId, UUID productId, Instant fileUploadedAt, Instant now) {
                throw new DataAccessResourceFailureException("database is down");
            }
        };
        ProductDownloadService broken = new ProductDownloadService(failing, storage, clock, transactionManager);

        assertThatThrownBy(() -> broken.issue(customer, product))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(records()).isZero();
    }

    @Test
    @DisplayName("SEC-011: sem Acesso o serviço devolve vazio e não grava Registro")
    void shouldReturnEmptyWithoutAccess() {
        UUID stranger = jdbc.queryForObject(
                "insert into customers (email, password_hash, email_confirmed_at)"
                        + " values (?, 'x', now()) returning id",
                UUID.class,
                "outro-" + UUID.randomUUID() + "@example.com");

        assertThat(service.issue(stranger, product)).isEmpty();
        assertThat(jdbc.queryForObject(
                        "select count(*) from download_records where customer_id = ?", Long.class, stranger))
                .isZero();
    }
}
