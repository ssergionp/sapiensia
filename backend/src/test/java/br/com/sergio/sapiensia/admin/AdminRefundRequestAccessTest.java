package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.CUSTOMER_PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminOrderFixtures.insertCustomer;
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

/** SEC-010, SEC-029: who may read the Administrator's list of Refund requests. */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class, SwitchablePixGatewayConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-orders", "app.login-limit.ip-max-attempts=1000"})
class AdminRefundRequestAccessTest {

    private static final String REFUNDS = "/api/admin/refund-requests";

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
        account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        customerEmail = "comprador-" + UUID.randomUUID() + "@example.com";
        insertCustomer(jdbc, passwordEncoder, customerEmail);
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
        return mvc.perform(get(REFUNDS).with(authentication(authentication)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    @DisplayName("SEC-010: anônimo recebe 401 e Cliente recebe 403")
    void shouldDenyAnonymousAndCustomers() throws Exception {
        HttpResponse<String> anonymous = new AdminBrowser(port).get(REFUNDS);
        AdminBrowser customer = new AdminBrowser(port);
        assertThat(customer.loginCustomer(customerEmail, CUSTOMER_PASSWORD).statusCode())
                .isEqualTo(204);

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(customer.get(REFUNDS).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("SEC-029: sem REFUNDS_VIEW, mesmo com ORDERS_VIEW, recebe 403")
    void shouldDenyWithoutRefundsViewEvenWithOrdersView() throws Exception {
        Set<Capability> all = EnumSet.allOf(Capability.class);
        all.remove(Capability.REFUNDS_VIEW);

        assertThat(listStatus(session(EnumSet.of(Capability.ORDERS_VIEW), true, true)))
                .isEqualTo(403);
        assertThat(listStatus(session(all, true, true))).isEqualTo(403);
    }

    @Test
    @DisplayName("SEC-010: REFUNDS_VIEW só com a senha, ou só com o TOTP, recebe 403")
    void shouldDenyRefundsViewWithOneFactorOnly() throws Exception {
        Set<Capability> view = EnumSet.of(Capability.REFUNDS_VIEW);

        assertThat(listStatus(session(view, true, false))).isEqualTo(403);
        assertThat(listStatus(session(view, false, true))).isEqualTo(403);
    }

    @Test
    @DisplayName("SEC-010 SEC-029: REFUNDS_VIEW com os dois fatores recebe 200")
    void shouldLetRefundsViewWithBothFactorsIn() throws Exception {
        assertThat(listStatus(session(EnumSet.of(Capability.REFUNDS_VIEW), true, true)))
                .isEqualTo(200);
    }

    @Test
    @DisplayName("SEC-010: só a senha, sem o código, recebe 401 pela sessão real")
    void shouldDenyThePasswordWithoutTheCode() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        clock.advance(java.time.Duration.ofSeconds(30));
        assertThat(browser.loginAdmin(account.email(), AdminProductFixtures.PASSWORD)
                        .statusCode())
                .isEqualTo(204);

        assertThat(browser.get(REFUNDS).statusCode()).isEqualTo(401);
    }
}
