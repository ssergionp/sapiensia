package br.com.sergio.sapiensia.security;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.admin.AdministratorPrincipal;
import br.com.sergio.sapiensia.admin.Capability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** The panel rule has two independent locks: the capability and the two factors. */
class PanelAuthorizationTest {

    private static final String PANEL = Capability.PANEL_ACCESS.authority();
    private static final String PASSWORD = FactorGrantedAuthority.PASSWORD_AUTHORITY;
    private static final String TOTP = AdministratorPrincipal.TOTP_AUTHORITY;

    private static boolean granted(String... authorities) {
        var authentication = new TestingAuthenticationToken("someone", null, authorities);
        return SecurityConfig.panel(Capability.PANEL_ACCESS)
                .authorize(() -> authentication, new RequestAuthorizationContext(new MockHttpServletRequest()))
                .isGranted();
    }

    @Test
    @DisplayName("SEC-001: o painel exige a capacidade E os fatores senha e TOTP")
    void shouldRequireTheCapabilityAndBothFactors() {
        assertThat(granted(PANEL, PASSWORD, TOTP)).isTrue();
        assertThat(granted(PANEL)).as("capability without factors").isFalse();
        assertThat(granted(PANEL, PASSWORD)).as("capability without TOTP").isFalse();
        assertThat(granted(PANEL, TOTP)).as("capability without password").isFalse();
        assertThat(granted(PASSWORD, TOTP)).as("factors without capability").isFalse();
        assertThat(granted(PASSWORD)).as("the pending state").isFalse();
    }
}
