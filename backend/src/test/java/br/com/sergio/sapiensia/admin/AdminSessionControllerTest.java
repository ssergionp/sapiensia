package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.customer.ClientIpResolver;
import br.com.sergio.sapiensia.customer.PasswordLogin;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

class AdminSessionControllerTest {

    @Test
    @DisplayName("SEC-065: shouldAnswerUnavailableWhenTheAuditFailsWithAnIllegalState")
    void shouldAnswerUnavailableWhenTheAuditFailsWithAnIllegalState() {
        AuditLog audit = mock(AuditLog.class);
        doThrow(new IllegalStateException("no transaction")).when(audit).recordInTransaction(any(AuditEntry.class));
        AdminSessionController controller = new AdminSessionController(
                mock(Administrators.class),
                mock(PasswordEncoder.class),
                mock(PasswordLogin.class),
                audit,
                mock(ClientIpResolver.class),
                mock(PlatformTransactionManager.class),
                Clock.systemUTC());
        var loaded = new AdministratorUserDetailsService.Loaded(UUID.randomUUID(), "a@example.com", 0, "hash");

        assertThatThrownBy(() -> controller.accept(
                        new TestingAuthenticationToken(loaded, null),
                        new MockHttpServletRequest(),
                        new MockHttpServletResponse()))
                .isInstanceOf(AuditUnavailableException.class);
    }
}
