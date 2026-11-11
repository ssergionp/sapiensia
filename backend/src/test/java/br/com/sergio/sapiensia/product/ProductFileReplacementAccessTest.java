package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.sergio.sapiensia.admin.AdministratorPrincipal;
import br.com.sergio.sapiensia.admin.Capability;
import br.com.sergio.sapiensia.admin.RecentAuthentication;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.customer.ClientIpResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** The decisions of the lock of the file route, one by one, without a server (Mockito is fine in a unit test). */
class ProductFileReplacementAccessTest {

    private final Products products = mock(Products.class);
    private final RecentAuthentication recent = mock(RecentAuthentication.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final ClientIpResolver clientIp = mock(ClientIpResolver.class);
    private final ProductFileReplacementAccess access =
            new ProductFileReplacementAccess(products, recent, audit, clientIp);
    private final UUID productId = UUID.randomUUID();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        when(clientIp.resolve(any())).thenReturn("203.0.113.9");
        var principal = new AdministratorPrincipal(
                UUID.randomUUID(),
                "painel@example.com",
                0,
                EnumSet.allOf(Capability.class),
                Instant.now(),
                Instant.now());
        List<GrantedAuthority> authorities = new ArrayList<>();
        principal.capabilities().forEach(c -> authorities.add(new SimpleGrantedAuthority(c.authority())));
        authorities.add(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                .issuedAt(Instant.now())
                .build());
        authorities.add(FactorGrantedAuthority.withAuthority(AdministratorPrincipal.TOTP_AUTHORITY)
                .issuedAt(Instant.now())
                .build());
        authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        request.setRequestURI("/api/admin/products/" + productId + "/file");
    }

    private AuthorizationResult authorize(String id) {
        return access.authorize(() -> authentication, new RequestAuthorizationContext(request, Map.of("id", id)));
    }

    @Test
    @DisplayName("SEC-008: banco fora ao ler o marcador nega com Unavailable (503), sem consultar a autenticação")
    void shouldAnswerUnavailableWhenTheDatabaseIsDown() {
        when(products.everPublished(productId)).thenThrow(new DataAccessResourceFailureException("down"));

        AuthorizationResult result = authorize(productId.toString());

        assertThat(result).isInstanceOf(ProductFileReplacementAccess.Unavailable.class);
        assertThat(result.isGranted()).isFalse();
        verifyNoInteractions(recent);
    }

    @Test
    @DisplayName("SEC-008: id que não é UUID é concedido, para o controller responder como antes")
    void shouldGrantAnIdThatIsNotAUuid() {
        assertThat(authorize("not-a-uuid").isGranted()).isTrue();

        verifyNoInteractions(products);
        assertThat(request.getAttribute(ProductFileReplacementAccess.REAUTHENTICATED))
                .isNull();
    }

    @Test
    @DisplayName("SEC-008: rascunho é concedido sem consultar a autenticação e sem marcar o pedido")
    void shouldGrantADraftWithoutMarkingTheRequest() {
        when(products.everPublished(productId)).thenReturn(false);

        assertThat(authorize(productId.toString()).isGranted()).isTrue();

        verifyNoInteractions(recent);
        assertThat(request.getAttribute(ProductFileReplacementAccess.REAUTHENTICATED))
                .isNull();
    }

    @Test
    @DisplayName("SEC-008: publicado com autenticação recente é concedido e marca o pedido como reautenticado")
    void shouldGrantAPublishedProductWithRecentAuthenticationAndMarkTheRequest() {
        when(products.everPublished(productId)).thenReturn(true);
        when(recent.isRecent(any())).thenReturn(true);

        assertThat(authorize(productId.toString()).isGranted()).isTrue();

        assertThat(request.getAttribute(ProductFileReplacementAccess.REAUTHENTICATED))
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("SEC-008: publicado sem autenticação recente é negado com ReauthenticationRequired e auditado")
    void shouldDenyAPublishedProductWithoutRecentAuthenticationAndAuditIt() {
        when(products.everPublished(productId)).thenReturn(true);
        when(recent.isRecent(any())).thenReturn(false);

        AuthorizationResult result = authorize(productId.toString());

        assertThat(result).isInstanceOf(ProductFileReplacementAccess.ReauthenticationRequired.class);
        assertThat(result.isGranted()).isFalse();
        verify(audit).recordIndependent(any());
        assertThat(request.getAttribute(ProductFileReplacementAccess.REAUTHENTICATED))
                .isNull();
    }
}
