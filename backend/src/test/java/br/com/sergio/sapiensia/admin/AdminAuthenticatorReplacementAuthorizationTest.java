package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
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
import org.springframework.http.MediaType;
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

/** SEC-010, SEC-008: who reaches the two authenticator replacement routes. */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "test.isolated-context=admin-authenticator-replacement-authz",
            "app.login-limit.ip-max-attempts=1000"
        })
class AdminAuthenticatorReplacementAuthorizationTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow
    // csrf() of MockMvc would swap the repository of the shared CsrfFilter, so the real server would stop sending
    // the cookie; the cookie repository accepts a matching cookie and header
    private static final String CSRF = "test-csrf-token";
    private static final List<String> ROUTES =
            List.of("/api/admin/authenticator-replacement", "/api/admin/authenticator-replacement/confirmation");

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

    private MockMvc mvc;
    private AdminProductFixtures.Admin account;
    private String customerEmail;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        customerEmail = "cliente-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, ?, now())",
                customerEmail,
                passwordEncoder.encode(PASSWORD));
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

    private int status(String route, Authentication authentication) throws Exception {
        return mvc.perform(post(route)
                        .with(authentication(authentication))
                        .cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", CSRF))
                        .header("X-XSRF-TOKEN", CSRF)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    @DisplayName("SEC-010: anônimo recebe 401 e Cliente 403 nas duas rotas")
    void shouldDenyAnonymousAndCustomer() throws Exception {
        AdminBrowser anonymous = new AdminBrowser(port);
        AdminBrowser customer = new AdminBrowser(port);
        assertThat(customer.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);
        for (String route : ROUTES) {
            assertThat(anonymous.post(route, "{}").statusCode())
                    .as("anonymous " + route)
                    .isEqualTo(401);
            assertThat(customer.post(route, "{}").statusCode())
                    .as("customer " + route)
                    .isEqualTo(403);
        }
    }

    @Test
    @DisplayName("SEC-010: o Administrador pendente (só a senha) recebe 401, como nas outras rotas do painel")
    void shouldDenyThePendingAdministrator() throws Exception {
        AdminBrowser pending = new AdminBrowser(port);
        assertThat(pending.loginAdmin(account.email(), PASSWORD).statusCode()).isEqualTo(204);
        for (String route : ROUTES) {
            assertThat(pending.post(route, "{}").statusCode()).as(route).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("SEC-008: sem o token CSRF as duas rotas respondem 403")
    void shouldRequireTheCsrfToken() throws Exception {
        AdminBrowser full = new AdminBrowser(port);
        assertThat(full.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
        for (String route : ROUTES) {
            assertThat(full.postWithoutCsrf(route, "{}").statusCode()).as(route).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("SEC-010: sem AUTHENTICATOR_REPLACE a troca é negada; com ela a rota é alcançada")
    void shouldDenyWithoutTheCapability() throws Exception {
        Authentication panelOnly = session(EnumSet.of(Capability.PANEL_ACCESS, Capability.RECOVERY_CODES_MANAGE));
        Authentication allowed = session(EnumSet.of(Capability.AUTHENTICATOR_REPLACE));
        for (String route : ROUTES) {
            assertThat(status(route, panelOnly))
                    .as("without the capability " + route)
                    .isEqualTo(403);
            assertThat(status(route, allowed))
                    .as("with the capability " + route)
                    .isNotEqualTo(403);
        }
    }
}
