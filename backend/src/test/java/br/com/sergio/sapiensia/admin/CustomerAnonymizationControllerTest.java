package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.customer.CustomerAnonymizationAccess;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * FR-036, SEC-038: POST /api/admin/customer-anonymizations. It lives in the admin package because the browser and
 * session helpers of the panel tests are package-private.
 */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=customer-anonymization", "app.login-limit.ip-max-attempts=1000"})
class CustomerAnonymizationControllerTest {

    private static final String ROUTE = "/api/admin/customer-anonymizations";
    private static final String REAUTHENTICATION = "/api/admin/sessions/reauthentication";
    private static final Duration OUT_OF_WINDOW = Duration.ofMinutes(5).plusSeconds(1);

    @LocalServerPort
    int port;

    @Autowired
    CustomerAnonymizationAccess access;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AdminMfaTestSupport.MutableClock clock;

    private AdminProductFixtures.Admin account;
    private String customerEmail;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        customerEmail = "fulano-" + UUID.randomUUID() + "@example.com";
        customerId = AdminOrderFixtures.insertCustomer(jdbc, passwordEncoder, customerEmail);
    }

    private static String body(String email) {
        return "{\"email\":\"" + email + "\"}";
    }

    private AdminBrowser fullSession() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginAdminFully(account.email(), AdminProductFixtures.PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
        return browser;
    }

    private String storedEmail() {
        return jdbc.queryForObject("select email from customers where id = ?", String.class, customerId);
    }

    private long auditRows(String result) {
        return jdbc.queryForObject(
                "select count(*) from audit_events where action = 'ADMIN_CUSTOMER_ANONYMIZED' and result = ?"
                        + " and actor_id = ?",
                Long.class,
                result,
                account.id());
    }

    private Authentication session(Set<Capability> capabilities) {
        var principal = new AdministratorPrincipal(
                account.id(), account.email(), 0, capabilities, Instant.now(), Instant.now());
        List<GrantedAuthority> authorities = new ArrayList<>();
        capabilities.forEach(c -> authorities.add(new SimpleGrantedAuthority(c.authority())));
        authorities.add(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(Instant.now())
                .build());
        authorities.add(FactorGrantedAuthority.withAuthority(AdministratorPrincipal.TOTP_AUTHORITY)
                .issuedAt(Instant.now())
                .build());
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    @Test
    @DisplayName("FR-036, SEC-038: o Administrador com os dois fatores e autenticação recente anonimiza a conta")
    void shouldAnonymizeTheAccount() throws Exception {
        AdminBrowser admin = fullSession();

        HttpResponse<String> answer = admin.post(ROUTE, body(customerEmail));

        assertThat(answer.statusCode()).isEqualTo(204);
        assertThat(answer.body()).isEmpty();
        assertThat(storedEmail()).endsWith("@anonimizado.invalid").doesNotContain(customerEmail);
        assertThat(auditRows("SUCCESS")).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-038: anônimo recebe 401 e nada muda")
    void shouldDenyAnonymous() throws Exception {
        HttpResponse<String> answer = new AdminBrowser(port).post(ROUTE, body(customerEmail));

        assertThat(answer.statusCode()).isEqualTo(401);
        assertThat(storedEmail()).isEqualTo(customerEmail);
    }

    @Test
    @DisplayName("SEC-038: Administrador só com a senha recebe 401 e nada muda")
    void shouldDenyThePasswordWithoutTheCode() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        clock.advance(Duration.ofSeconds(30));
        assertThat(browser.loginAdmin(account.email(), AdminProductFixtures.PASSWORD)
                        .statusCode())
                .isEqualTo(204);

        HttpResponse<String> answer = browser.post(ROUTE, body(customerEmail));

        assertThat(answer.statusCode()).isEqualTo(401);
        assertThat(storedEmail()).isEqualTo(customerEmail);
    }

    @Test
    @DisplayName("SEC-038: Administrador sem a capacidade CUSTOMERS_ANONYMIZE recebe 403 e nada muda")
    void shouldDenyWithoutTheCapability() throws Exception {
        Set<Capability> others = EnumSet.allOf(Capability.class);
        others.remove(Capability.CUSTOMERS_ANONYMIZE);
        // MockMvc's csrf() post-processor would swap the token repository of the shared filter chain, so the decision
        // of the access manager is asked directly; a plain denial is the standard 403 of the access denied handler
        var request = new MockHttpServletRequest("POST", ROUTE);

        AuthorizationResult withoutIt =
                access.authorize(() -> session(others), new RequestAuthorizationContext(request));
        AuthorizationResult withIt = access.authorize(
                () -> session(EnumSet.allOf(Capability.class)), new RequestAuthorizationContext(request));

        assertThat(withoutIt.isGranted()).isFalse();
        assertThat(withoutIt).isNotInstanceOf(CustomerAnonymizationAccess.ReauthenticationRequired.class);
        assertThat(withIt.isGranted()).isTrue();
        assertThat(storedEmail()).isEqualTo(customerEmail);
    }

    @Test
    @DisplayName("SEC-038: autenticação velha recebe 403 REAUTHENTICATION_REQUIRED e nada muda")
    void shouldRequireARecentAuthentication() throws Exception {
        AdminBrowser admin = fullSession();
        clock.advance(OUT_OF_WINDOW);

        HttpResponse<String> answer = admin.post(ROUTE, body(customerEmail));

        assertThat(answer.statusCode()).isEqualTo(403);
        assertThat(answer.body()).contains("\"reauthentication\":\"REAUTHENTICATION_REQUIRED\"");
        assertThat(answer.body()).doesNotContain(customerEmail);
        assertThat(storedEmail()).isEqualTo(customerEmail);
        assertThat(auditRows("SUCCESS")).isZero();

        // after proving both factors again the same request goes through
        clock.advance(Duration.ofSeconds(30));
        String code = AdminMfaTestSupport.codeAt(account.secret(), clock.instant());
        assertThat(admin.post(
                                REAUTHENTICATION,
                                "{\"password\":\"" + AdminProductFixtures.PASSWORD + "\",\"code\":\"" + code + "\"}")
                        .statusCode())
                .isEqualTo(204);
        assertThat(admin.post(ROUTE, body(customerEmail)).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("FR-036: e-mail desconhecido recebe 404 CUSTOMER_NOT_FOUND e a tentativa fica na auditoria")
    void shouldAnswer404ForAnUnknownEmailAndAuditTheAttempt() throws Exception {
        AdminBrowser admin = fullSession();
        String unknown = "ninguem-" + UUID.randomUUID() + "@example.com";

        HttpResponse<String> answer = admin.post(ROUTE, body(unknown));

        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.body())
                .contains("\"title\":\"Customer not found\"")
                .contains("CUSTOMER_NOT_FOUND")
                .doesNotContain(unknown);
        assertThat(auditRows("FAILURE")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'ADMIN_CUSTOMER_ANONYMIZED'"
                                + " and result = 'FAILURE' and actor_id = ? and object_type = 'CUSTOMER'"
                                + " and object_id is null",
                        Long.class,
                        account.id()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "FR-036: conta com Pedido pendente recebe 409 CUSTOMER_HAS_PENDING_ORDER e a tentativa fica na auditoria")
    void shouldAnswer409ForAPendingOrderAndAuditTheAttempt() throws Exception {
        UUID productId = AdminOrderFixtures.insertProduct(jdbc, "Guia");
        AdminOrderFixtures.insertOrder(
                jdbc,
                customerId,
                productId,
                4990,
                "PENDING",
                Instant.now().minusSeconds(60),
                Instant.now().plusSeconds(1200));
        AdminBrowser admin = fullSession();

        HttpResponse<String> answer = admin.post(ROUTE, body(customerEmail));

        assertThat(answer.statusCode()).isEqualTo(409);
        assertThat(answer.body())
                .contains("\"title\":\"Customer has a pending order\"")
                .contains("CUSTOMER_HAS_PENDING_ORDER")
                .doesNotContain(customerEmail);
        assertThat(storedEmail()).isEqualTo(customerEmail);
        assertThat(auditRows("SUCCESS")).isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_events where action = 'ADMIN_CUSTOMER_ANONYMIZED'"
                                + " and result = 'FAILURE' and actor_id = ? and object_type = 'CUSTOMER'"
                                + " and object_id = ?",
                        Long.class,
                        account.id(),
                        customerId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FR-036: segunda chamada com o mesmo e-mail recebe 404 e nenhuma auditoria de sucesso nova")
    void shouldAnswer404OnTheSecondCall() throws Exception {
        AdminBrowser admin = fullSession();
        assertThat(admin.post(ROUTE, body(customerEmail)).statusCode()).isEqualTo(204);
        String afterFirst = storedEmail();

        HttpResponse<String> second = admin.post(ROUTE, body(customerEmail));

        assertThat(second.statusCode()).isEqualTo(404);
        assertThat(second.body()).contains("CUSTOMER_NOT_FOUND");
        assertThat(storedEmail()).isEqualTo(afterFirst);
        assertThat(auditRows("SUCCESS")).isEqualTo(1);
        assertThat(auditRows("FAILURE")).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-036: e-mail com caixa e espaços diferentes acha o Cliente")
    void shouldFindTheCustomerWithAnotherCaseAndSpaces() throws Exception {
        AdminBrowser admin = fullSession();
        String typed = "  " + customerEmail.toUpperCase(Locale.ROOT) + " ";

        HttpResponse<String> answer = admin.post(ROUTE, body(typed));

        assertThat(answer.statusCode()).isEqualTo(204);
        assertThat(storedEmail()).endsWith("@anonimizado.invalid");
    }

    @Test
    @DisplayName("FR-036: corpo sem e-mail, em branco ou que não é e-mail recebe 400 e nada muda")
    void shouldRejectAnInvalidBody() throws Exception {
        AdminBrowser admin = fullSession();

        assertThat(admin.post(ROUTE, "{}").statusCode()).isEqualTo(400);
        assertThat(admin.post(ROUTE, body("   ")).statusCode()).isEqualTo(400);
        assertThat(admin.post(ROUTE, body("isto-nao-e-email")).statusCode()).isEqualTo(400);
        assertThat(storedEmail()).isEqualTo(customerEmail);
        assertThat(auditRows("SUCCESS")).isZero();
    }
}
