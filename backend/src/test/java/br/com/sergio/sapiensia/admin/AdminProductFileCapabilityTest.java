package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorage;
import br.com.sergio.sapiensia.product.SwitchableProductFileStorageConfig;
import br.com.sergio.sapiensia.product.TestPdfs;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
import org.springframework.mock.web.MockMultipartFile;
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
 * SEC-010, SEC-011: PRODUCTS_FILE and the two factors are independent locks on the upload. A real login always gives
 * every capability, so the other states are built by hand. Its own context: the MockMvc {@code csrf()} post-processor
 * replaces the CSRF repository of the filter chain, which would break the real-HTTP tests that share a context.
 */
@Import({TestcontainersConfiguration.class, SwitchableProductFileStorageConfig.class})
@SpringBootTest(
        properties = {
            "test.isolated-context=admin-product-file-capability",
            "app.storage.file.dir=target/test-storage-file-capability"
        })
class AdminProductFileCapabilityTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SecretCipher cipher;

    @Autowired
    SwitchableProductFileStorage storage;

    private AdminProductFixtures.Admin account;
    private UUID existing;

    @BeforeEach
    void setUp() {
        clearState();
        account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        existing = AdminProductFixtures.insertProduct(jdbc);
    }

    @AfterEach
    void tearDown() {
        clearState();
    }

    private void clearState() {
        for (String key : storage.keys()) {
            storage.delete(key);
        }
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

    private MvcResult upload(Authentication authentication, UUID id) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        return mvc.perform(multipart("/api/admin/products/" + id + "/file")
                        .file(new MockMultipartFile("file", "guia.pdf", "application/pdf", TestPdfs.pages(1)))
                        .with(authentication(authentication))
                        .with(csrf()))
                .andReturn();
    }

    private void assertNothingStored() {
        assertThat(storage.keys()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from product_files", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName(
            "SEC-010 SEC-011: sem PRODUCTS_FILE a rota nega igual exista o Produto ou não, e o Storage fica intacto")
    void shouldDenyWithoutTheCapabilityTheSameForAnyId() throws Exception {
        Authentication withoutFile = session(
                EnumSet.of(Capability.PANEL_ACCESS, Capability.PRODUCTS_EDIT, Capability.PRODUCTS_PRICE), true, true);

        MvcResult forExisting = upload(withoutFile, existing);
        MvcResult forMissing = upload(withoutFile, UUID.randomUUID());

        assertThat(forExisting.getResponse().getStatus()).isEqualTo(403);
        assertThat(forMissing.getResponse().getStatus()).isEqualTo(403);
        assertThat(forMissing.getResponse().getContentAsString())
                .isEqualTo(forExisting.getResponse().getContentAsString());
        assertNothingStored();
    }

    @Test
    @DisplayName("SEC-010: PRODUCTS_FILE só com a senha, ou só com o TOTP, é negado e nada é gravado")
    void shouldDenyTheCapabilityWithoutBothFactors() throws Exception {
        Authentication passwordOnly = session(EnumSet.allOf(Capability.class), true, false);
        Authentication totpOnly = session(EnumSet.allOf(Capability.class), false, true);

        assertThat(upload(passwordOnly, existing).getResponse().getStatus()).isEqualTo(403);
        assertThat(upload(totpOnly, existing).getResponse().getStatus()).isEqualTo(403);
        assertNothingStored();
    }

    @Test
    @DisplayName("SEC-010: PRODUCTS_FILE com os dois fatores envia o arquivo (200)")
    void shouldLetTheCapabilityWithBothFactorsIn() throws Exception {
        Authentication full = session(EnumSet.of(Capability.PRODUCTS_FILE), true, true);

        assertThat(upload(full, existing).getResponse().getStatus()).isEqualTo(200);
        assertThat(storage.keys()).hasSize(1);
    }
}
