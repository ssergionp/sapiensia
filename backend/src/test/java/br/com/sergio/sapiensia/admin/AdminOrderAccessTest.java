package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.CUSTOMER_PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.ORDERS;
import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.insertCustomer;
import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.insertOrder;
import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.insertProduct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.payment.SwitchablePixGatewayConfig;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** SEC-010, SEC-001: who may read the Administrator's list of Orders. */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-orders", "app.login-limit.ip-max-attempts=1000"})
class AdminOrderAccessTest {

    private static final String TITLE = "Guia Secreto do Pedido";

    @LocalServerPort
    int port;

    @Autowired
    WebApplicationContext context;

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
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from orders");
        account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        customerEmail = "comprador-" + UUID.randomUUID() + "@example.com";
        UUID customer = insertCustomer(jdbc, passwordEncoder, customerEmail);
        UUID product = insertProduct(jdbc, TITLE);
        Instant now = clock.instant();
        insertOrder(jdbc, customer, product, 1000, "EXPIRED", now.minusSeconds(600), now.minusSeconds(300));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private Authentication session(Set<Capability> capabilities, boolean password, boolean totp) {
        var principal = new AdministratorPrincipal(
                account.id(), account.email(), 0, capabilities, Instant.now(), Instant.now());
        List<GrantedAuthority> authorities = new ArrayList<>();
        capabilities.forEach(c -> authorities.add(new SimpleGrantedAuthority(c.authority())));
        if (password) {
            authorities.add(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                    .issuedAt(Instant.now())
                    .build());
        }
        if (totp) {
            authorities.add(FactorGrantedAuthority.withAuthority(AdministratorPrincipal.TOTP_AUTHORITY)
                    .issuedAt(Instant.now())
                    .build());
        }
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    private int listStatus(Authentication authentication) throws Exception {
        return mvc.perform(get(ORDERS).with(authentication(authentication)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int send(AdminBrowser browser, String method, String path) throws Exception {
        return switch (method) {
            case "POST" -> browser.post(path, "{}").statusCode();
            case "PUT" -> browser.put(path, "{}").statusCode();
            default -> browser.delete(path).statusCode();
        };
    }

    @Test
    @DisplayName("SEC-010: anônimo recebe 401 e Cliente recebe 403, sem nenhum dado")
    void shouldDenyAnonymousAndCustomers() throws Exception {
        HttpResponse<String> anonymous = new AdminBrowser(port).get(ORDERS);
        AdminBrowser customer = new AdminBrowser(port);
        assertThat(customer.loginCustomer(customerEmail, CUSTOMER_PASSWORD).statusCode())
                .isEqualTo(204);
        HttpResponse<String> asCustomer = customer.get(ORDERS);

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).doesNotContain(TITLE);
        assertThat(asCustomer.statusCode()).isEqualTo(403);
        assertThat(asCustomer.body()).doesNotContain(TITLE);
    }

    @Test
    @DisplayName("SEC-010 SEC-001: só a senha, sem o código, recebe 401")
    void shouldDenyThePasswordWithoutTheCode() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        clock.advance(java.time.Duration.ofSeconds(30));
        assertThat(browser.loginAdmin(account.email(), AdminProductFixtures.PASSWORD)
                        .statusCode())
                .isEqualTo(204);

        HttpResponse<String> answer = browser.get(ORDERS);

        assertThat(answer.statusCode()).isEqualTo(401);
        assertThat(answer.body()).doesNotContain(TITLE);
    }

    @Test
    @DisplayName("SEC-010: todas as capacidades menos ORDERS_VIEW, com os dois fatores, recebe 403")
    void shouldDenyEveryCapabilityButOrdersView() throws Exception {
        Set<Capability> all = EnumSet.allOf(Capability.class);
        all.remove(Capability.ORDERS_VIEW);

        assertThat(listStatus(session(all, true, true))).isEqualTo(403);
    }

    @Test
    @DisplayName("SEC-010: ORDERS_VIEW só com a senha, ou só com o TOTP, recebe 403")
    void shouldDenyOrdersViewWithOneFactorOnly() throws Exception {
        Set<Capability> view = EnumSet.of(Capability.ORDERS_VIEW);

        assertThat(listStatus(session(view, true, false))).isEqualTo(403);
        assertThat(listStatus(session(view, false, true))).isEqualTo(403);
    }

    @Test
    @DisplayName("SEC-010: ORDERS_VIEW com os dois fatores recebe 200")
    void shouldLetOrdersViewWithBothFactorsIn() throws Exception {
        assertThat(listStatus(session(EnumSet.of(Capability.ORDERS_VIEW), true, true)))
                .isEqualTo(200);
    }

    @Test
    @DisplayName(
            "SEC-010: POST, PUT, DELETE e /api/admin/orders/{id} seguem negados (403) até para o Administrador completo")
    void shouldKeepOtherVerbsAndTheItemRouteDenied() throws Exception {
        AdminBrowser admin = new AdminBrowser(port);
        assertThat(admin.loginAdminFully(account.email(), AdminProductFixtures.PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
        String item = ORDERS + "/" + UUID.randomUUID();

        assertThat(send(admin, "POST", ORDERS)).isEqualTo(403);
        assertThat(send(admin, "PUT", ORDERS)).isEqualTo(403);
        assertThat(send(admin, "DELETE", ORDERS)).isEqualTo(403);
        assertThat(admin.get(item).statusCode()).isEqualTo(403);
        assertThat(send(admin, "PUT", item)).isEqualTo(403);
        assertThat(send(admin, "DELETE", item)).isEqualTo(403);
    }
}
