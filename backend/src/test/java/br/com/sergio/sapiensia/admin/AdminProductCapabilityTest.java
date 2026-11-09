package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.validBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/**
 * SEC-010, SEC-029, ADR-0004: the capability and the two factors are independent locks. A real login always gives the
 * Administrator every capability and both factors, so the other states are built by hand here.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"test.isolated-context=admin-product-capability"})
class AdminProductCapabilityTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecretCipher cipher;

    private MockMvc mvc;
    private AdminProductFixtures.Admin account;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.update("delete from products");
        // the session version filter ends a session whose Administrator is not in the table with the same version
        account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
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

    private int createStatus(Authentication authentication) throws Exception {
        return mvc.perform(post(PRODUCTS)
                        .with(authentication(authentication))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int listStatus(Authentication authentication) throws Exception {
        return mvc.perform(get(PRODUCTS).with(authentication(authentication)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from products", Integer.class);
    }

    @Test
    @DisplayName("SEC-010: só PANEL_ACCESS com os dois fatores recebe 403 e nada é gravado")
    void shouldDenyAnAdministratorWithoutTheProductCapability() throws Exception {
        Authentication panelOnly = session(EnumSet.of(Capability.PANEL_ACCESS), true, true);

        assertThat(createStatus(panelOnly)).isEqualTo(403);
        assertThat(listStatus(panelOnly)).isEqualTo(403);
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("SEC-010: PRODUCTS_EDIT só com o fator senha recebe 403")
    void shouldDenyPasswordFactorOnly() throws Exception {
        Authentication passwordOnly = session(EnumSet.of(Capability.PRODUCTS_EDIT), true, false);

        assertThat(createStatus(passwordOnly)).isEqualTo(403);
        assertThat(listStatus(passwordOnly)).isEqualTo(403);
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("SEC-010: PRODUCTS_EDIT só com o fator TOTP recebe 403")
    void shouldDenyTotpFactorOnly() throws Exception {
        Authentication totpOnly = session(EnumSet.of(Capability.PRODUCTS_EDIT), false, true);

        assertThat(createStatus(totpOnly)).isEqualTo(403);
        assertThat(listStatus(totpOnly)).isEqualTo(403);
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("SEC-010: PRODUCTS_EDIT com os dois fatores cria (201) e lista (200)")
    void shouldLetProductsEditWithBothFactorsIn() throws Exception {
        Authentication editor = session(EnumSet.of(Capability.PRODUCTS_EDIT), true, true);

        assertThat(createStatus(editor)).isEqualTo(201);
        assertThat(listStatus(editor)).isEqualTo(200);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-029: PRODUCTS_PRICE com os dois fatores não cria nem lista Produtos")
    void shouldNotLetProductsPriceCreateOrList() throws Exception {
        Authentication priceOnly = session(EnumSet.of(Capability.PRODUCTS_PRICE), true, true);

        assertThat(createStatus(priceOnly)).isEqualTo(403);
        assertThat(listStatus(priceOnly)).isEqualTo(403);
        assertThat(count()).isZero();
    }
}
