package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.payment.CustomerHasPendingOrderException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CustomerAnonymizationTest {

    private static final String PASSWORD = "senha-antiga-123"; // gitleaks:allow
    private static final String ORIGIN = "203.0.113.9";
    private static final List<String> CUSTOMER_TABLES = List.of("orders", "accesses", "download_records");
    private static final List<String> DELETED_TABLES =
            List.of("email_confirmation_tokens", "password_reset_tokens", "customer_devices", "payment_customers");

    @Autowired
    private CustomerAnonymization anonymization;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerUserDetailsService userDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private WebApplicationContext context;

    private String email;
    private UUID customerId;
    private UUID administratorId;

    @BeforeEach
    void setUp() {
        email = "anon-" + UUID.randomUUID() + "@example.com";
        administratorId = UUID.randomUUID();
        customerId = jdbc.queryForObject(
                "insert into customers (email, password_hash, email_confirmed_at, first_login_at)"
                        + " values (?, ?, now(), now()) returning id",
                UUID.class,
                email,
                passwordEncoder.encode(PASSWORD));
    }

    @Test
    @DisplayName(
            "FR-036, SEC-038: anonimizar troca o e-mail, inutiliza a senha e apaga tokens, aparelhos e o vínculo com o Asaas")
    void shouldReplaceTheIdentityAndDeleteTheLinkedRows() {
        insertTokensDevicesAndAsaasLink();
        Map<String, Object> before = jdbc.queryForMap("select * from customers where id = ?", customerId);

        Optional<UUID> result = anonymization.anonymize(email, administratorId, ORIGIN);

        assertThat(result).contains(customerId);
        Map<String, Object> after = jdbc.queryForMap("select * from customers where id = ?", customerId);
        assertThat((String) after.get("email")).matches("^anonimizado-[0-9a-f-]{36}@anonimizado\\.invalid$");
        assertThat(passwordEncoder.matches(PASSWORD, (String) after.get("password_hash")))
                .isFalse();
        assertThat(after.get("password_hash")).isNotEqualTo(before.get("password_hash"));
        assertThat((Integer) after.get("session_version")).isEqualTo((Integer) before.get("session_version") + 1);
        assertThat(after.get("first_login_at")).isNull();
        assertThat(after.get("anonymized_at")).isNotNull();
        assertThat(after.get("created_at")).isEqualTo(before.get("created_at"));
        assertThat(after.get("email_confirmed_at")).isEqualTo(before.get("email_confirmed_at"));
        for (String table : DELETED_TABLES) {
            assertThat(countOfCustomer(table)).as(table).isZero();
        }
    }

    @Test
    @DisplayName("ADR-0007, SEC-038: Pedidos, Acessos, Registros de download e eventos de pagamento ficam iguais")
    void shouldLeaveTheFinancialRecordsUntouched() {
        String providerEventId = insertOrderAccessDownloadAndEvent();
        Map<String, List<Map<String, Object>>> before = snapshot(providerEventId);
        assertThat(before.values()).allSatisfy(rows -> assertThat(rows).isNotEmpty());

        Optional<UUID> result = anonymization.anonymize(email, administratorId, ORIGIN);

        assertThat(result).contains(customerId);
        assertThat(snapshot(providerEventId)).isEqualTo(before);
        assertThat(countOfCustomer("customers")).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-038: a anonimização fica na auditoria sem o e-mail")
    void shouldAuditWithoutTheEmail() {
        anonymization.anonymize(email, administratorId, ORIGIN);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select action, result, object_type, actor_id, row_to_json(a)::text as dump"
                        + " from audit_events a where object_id = ?",
                customerId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("action")).isEqualTo("ADMIN_CUSTOMER_ANONYMIZED");
        assertThat(row.get("result")).isEqualTo("SUCCESS");
        assertThat(row.get("object_type")).isEqualTo("CUSTOMER");
        assertThat(row.get("actor_id")).isEqualTo(administratorId);
        assertThat((String) row.get("dump")).doesNotContain(email);
    }

    @Test
    @DisplayName("FR-036: a sessão aberta do Cliente cai na anonimização")
    void shouldEndTheOpenSessionOfTheCustomer() throws Exception {
        var principal = ((CustomerUserDetailsService.CustomerPrincipal) userDetailsService.loadUserByUsername(email))
                .withoutPassword();
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        assertThat(mvc.perform(get("/api/sessions/current").session(session))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        anonymization.anonymize(email, administratorId, ORIGIN);

        assertThat(mvc.perform(get("/api/sessions/current").session(session))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("FR-036: a pessoa pode se cadastrar de novo com o mesmo e-mail")
    void shouldLetThePersonRegisterAgainWithTheSameEmail() {
        anonymization.anonymize(email, administratorId, ORIGIN);

        customerService.register(email, "uma senha longa 1");

        assertThat(jdbc.queryForObject("select count(*) from customers where email = ?", Long.class, email))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select id from customers where email = ?", UUID.class, email))
                .isNotEqualTo(customerId);
    }

    @Test
    @DisplayName("FR-036: e-mail desconhecido não muda nada")
    void shouldChangeNothingForAnUnknownEmail() {
        Long customers = jdbc.queryForObject("select count(*) from customers", Long.class);
        Long audit = jdbc.queryForObject("select count(*) from audit_events", Long.class);

        Optional<UUID> result =
                anonymization.anonymize("ninguem-" + UUID.randomUUID() + "@example.com", administratorId, ORIGIN);

        assertThat(result).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from customers", Long.class))
                .isEqualTo(customers);
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Long.class))
                .isEqualTo(audit);
        assertThat(jdbc.queryForObject(
                        "select anonymized_at is null from customers where id = ?", Boolean.class, customerId))
                .isTrue();
    }

    private void insertPendingOrder(String expiresAtSql) {
        UUID productId = jdbc.queryForObject(
                "insert into products (title, description, category, price_cents, status, first_published_at)"
                        + " values ('Guia', 'd', 'LAW', 4990, 'PUBLISHED', now()) returning id",
                UUID.class);
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at)"
                        + " values (?, ?, ?, 4990, 'PENDING', ?, now() - interval '2 hours', " + expiresAtSql + ")",
                UUID.randomUUID(),
                customerId,
                productId,
                "pay_" + UUID.randomUUID());
    }

    @Test
    @DisplayName("FR-036, SEC-038: conta com Pedido pendente não é anonimizada e nada muda")
    void shouldRefuseAnAccountWithAPendingOrderAndChangeNothing() {
        insertTokensDevicesAndAsaasLink();
        insertPendingOrder("now() + interval '20 minutes'");
        Map<String, Object> before = jdbc.queryForMap("select * from customers where id = ?", customerId);
        Long audit = jdbc.queryForObject("select count(*) from audit_events", Long.class);

        assertThatThrownBy(() -> anonymization.anonymize(email, administratorId, ORIGIN))
                .isInstanceOf(CustomerHasPendingOrderException.class);

        assertThat(jdbc.queryForMap("select * from customers where id = ?", customerId))
                .isEqualTo(before);
        for (String table : DELETED_TABLES) {
            assertThat(countOfCustomer(table)).as(table).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from audit_events", Long.class))
                .isEqualTo(audit);
    }

    @Test
    @DisplayName("FR-036, SEC-038: Pedido pendente já vencido também impede a anonimização")
    void shouldRefuseAPendingOrderPastItsExpiry() {
        insertPendingOrder("now() - interval '1 hour'");

        assertThatThrownBy(() -> anonymization.anonymize(email, administratorId, ORIGIN))
                .isInstanceOf(CustomerHasPendingOrderException.class);

        assertThat(jdbc.queryForObject("select email from customers where id = ?", String.class, customerId))
                .isEqualTo(email);
    }

    @Test
    @DisplayName("SEC-038: anonimização espera a compra em andamento do mesmo Cliente")
    void shouldWaitForAPurchaseInFlightOfTheSameCustomer() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch anonymizationStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // behaves like OrderService: advisory lock first, then rows with a foreign key to the Customer
        CompletableFuture<Void> purchase = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject(
                    "select pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class, "order:" + customerId);
            holding.countDown();
            try {
                anonymizationStarted.await(10, TimeUnit.SECONDS);
                jdbc.update(
                        "insert into payment_customers (customer_id, provider_customer_id) values (?, ?)",
                        customerId,
                        "cus_" + hash().substring(0, 20));
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        CompletableFuture<Optional<UUID>> anonymizing;
        try {
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            anonymizing = CompletableFuture.supplyAsync(() -> anonymization.anonymize(email, administratorId, ORIGIN));
            // gives the anonymization time to reach its first lock before the purchase touches the Customer's rows
            Thread.sleep(1000);
            anonymizationStarted.countDown();

            assertThatThrownBy(() -> anonymizing.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
        } finally {
            anonymizationStarted.countDown();
            release.countDown();
        }

        assertThatCode(() -> purchase.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
        assertThat(anonymizing.get(30, TimeUnit.SECONDS)).contains(customerId);
        // the purchase committed only the Asaas link, no Order: the anonymization went on and forgot it
        assertThat(countOfCustomer("payment_customers")).isZero();
    }

    /** Rows of the Customer in the table (for "customers", the Customer's own row). */
    private long countOfCustomer(String table) {
        String column = table.equals("customers") ? "id" : "customer_id";
        return jdbc.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?", Long.class, customerId);
    }

    private void insertTokensDevicesAndAsaasLink() {
        jdbc.update(
                "insert into email_confirmation_tokens (customer_id, token_hash, expires_at)"
                        + " values (?, ?, now() + interval '1 day')",
                customerId,
                hash());
        jdbc.update(
                "insert into password_reset_tokens (customer_id, token_hash, expires_at)"
                        + " values (?, ?, now() + interval '1 day')",
                customerId,
                hash());
        jdbc.update("insert into customer_devices (customer_id, token_hash) values (?, ?)", customerId, hash());
        jdbc.update(
                "insert into payment_customers (customer_id, provider_customer_id) values (?, ?)",
                customerId,
                "cus_" + hash().substring(0, 20));
    }

    private static String hash() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Inserts a paid Order with its Access, a Download record, a payment event and its result; returns the event id. */
    private String insertOrderAccessDownloadAndEvent() {
        UUID productId = jdbc.queryForObject(
                "insert into products (title, description, category, price_cents, status, first_published_at)"
                        + " values ('Guia', 'd', 'LAW', 4990, 'PUBLISHED', now()) returning id",
                UUID.class);
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "insert into orders (id, customer_id, product_id, price_cents, status, provider_payment_id,"
                        + " created_at, expires_at, paid_at)"
                        + " values (?, ?, ?, 4990, 'PAID', ?, now(), now() + interval '30 minutes', now())",
                orderId,
                customerId,
                productId,
                "pay_" + UUID.randomUUID());
        jdbc.update(
                "insert into accesses (customer_id, product_id, order_id, granted_at) values (?, ?, ?, now())",
                customerId,
                productId,
                orderId);
        jdbc.update(
                "insert into download_records (customer_id, product_id, file_uploaded_at, downloaded_at)"
                        + " values (?, ?, now(), now())",
                customerId,
                productId);
        String providerEventId = "evt_" + UUID.randomUUID();
        UUID eventId = jdbc.queryForObject(
                "insert into payment_events (provider_event_id, event_type, payload, received_at)"
                        + " values (?, 'PAYMENT_RECEIVED', '{\"k\":1}'::jsonb, now()) returning id",
                UUID.class,
                providerEventId);
        jdbc.update(
                "insert into payment_event_results (payment_event_id, result, order_id, processed_at)"
                        + " values (?, 'PAID', ?, now())",
                eventId,
                orderId);
        return providerEventId;
    }

    private Map<String, List<Map<String, Object>>> snapshot(String providerEventId) {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : CUSTOMER_TABLES) {
            rows.put(table, jdbc.queryForList("select * from " + table + " where customer_id = ?", customerId));
        }
        rows.put(
                "payment_events",
                jdbc.queryForList("select * from payment_events where provider_event_id = ?", providerEventId));
        rows.put(
                "payment_event_results",
                jdbc.queryForList(
                        "select r.* from payment_event_results r join payment_events e on e.id = r.payment_event_id"
                                + " where e.provider_event_id = ?",
                        providerEventId));
        return rows;
    }
}
