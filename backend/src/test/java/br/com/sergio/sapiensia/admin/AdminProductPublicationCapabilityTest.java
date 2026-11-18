package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * SEC-010, SEC-011: PRODUCTS_PUBLISH and the two factors are independent locks on the publication routes. A real login
 * always gives every capability, so the other states are built by hand. Its own context: the MockMvc {@code csrf()}
 * post-processor replaces the CSRF repository of the filter chain, which would break the real-HTTP tests that share a
 * context.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "test.isolated-context=admin-product-publication-capability")
class AdminProductPublicationCapabilityTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SecretCipher cipher;

    private AdminProductFixtures.Admin account;
    private UUID existing;

    @BeforeEach
    void setUp() {
        clearState();
        account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        existing = AdminProductFixtures.insertProductWithFileAndCover(jdbc);
    }

    @AfterEach
    void tearDown() {
        clearState();
    }

    private void clearState() {
        jdbc.update("delete from product_covers");
        jdbc.update("delete from product_files");
        jdbc.update("delete from products");
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

    private MvcResult publish(Authentication authentication, UUID id) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        return mvc.perform(put("/api/admin/products/" + id + "/publication")
                        .with(authentication(authentication))
                        .with(csrf()))
                .andReturn();
    }

    private MvcResult unpublish(Authentication authentication, UUID id) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        return mvc.perform(delete("/api/admin/products/" + id + "/publication")
                        .with(authentication(authentication))
                        .with(csrf()))
                .andReturn();
    }

    private Map<String, Object> productRow() {
        return jdbc.queryForMap("select * from products where id = ?", existing);
    }

    @Test
    @DisplayName("SEC-010 SEC-011: sem PRODUCTS_PUBLISH a rota nega igual exista o Produto ou não, e nada muda")
    void shouldDenyWithoutTheCapabilityTheSameForAnyId() throws Exception {
        Authentication withoutPublish = session(
                EnumSet.of(
                        Capability.PANEL_ACCESS,
                        Capability.PRODUCTS_EDIT,
                        Capability.PRODUCTS_PRICE,
                        Capability.PRODUCTS_FILE),
                true,
                true);
        Map<String, Object> before = productRow();

        MvcResult putExisting = publish(withoutPublish, existing);
        MvcResult putMissing = publish(withoutPublish, UUID.randomUUID());
        MvcResult deleteExisting = unpublish(withoutPublish, existing);
        MvcResult deleteMissing = unpublish(withoutPublish, UUID.randomUUID());

        for (MvcResult result : List.of(putExisting, putMissing, deleteExisting, deleteMissing)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
        }
        assertThat(putMissing.getResponse().getContentAsString())
                .isEqualTo(putExisting.getResponse().getContentAsString());
        assertThat(deleteMissing.getResponse().getContentAsString())
                .isEqualTo(deleteExisting.getResponse().getContentAsString());
        assertThat(productRow()).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-010: PRODUCTS_PUBLISH só com a senha, ou só com o TOTP, é negado")
    void shouldDenyTheCapabilityWithoutBothFactors() throws Exception {
        Authentication passwordOnly = session(EnumSet.allOf(Capability.class), true, false);
        Authentication totpOnly = session(EnumSet.allOf(Capability.class), false, true);
        Map<String, Object> before = productRow();

        assertThat(publish(passwordOnly, existing).getResponse().getStatus()).isEqualTo(403);
        assertThat(publish(totpOnly, existing).getResponse().getStatus()).isEqualTo(403);
        assertThat(unpublish(passwordOnly, existing).getResponse().getStatus()).isEqualTo(403);
        assertThat(unpublish(totpOnly, existing).getResponse().getStatus()).isEqualTo(403);
        assertThat(productRow()).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-010: PRODUCTS_PUBLISH com os dois fatores publica (200)")
    void shouldLetTheCapabilityWithBothFactorsIn() throws Exception {
        Authentication full = session(EnumSet.of(Capability.PRODUCTS_PUBLISH), true, true);

        assertThat(publish(full, existing).getResponse().getStatus()).isEqualTo(200);
        assertThat(productRow()).containsEntry("status", "PUBLISHED");
        assertThat(unpublish(full, existing).getResponse().getStatus()).isEqualTo(200);
        assertThat(productRow()).containsEntry("status", "UNPUBLISHED");
    }
}
