package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.sergio.sapiensia.admin.AdministratorPrincipal;
import br.com.sergio.sapiensia.admin.AdministratorSessions;
import br.com.sergio.sapiensia.admin.Capability;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SessionVersionFilterTest {

    private static final String EMAIL = "secret-person@example.com";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("SEC-006: se o banco falha ao conferir a sessão, a resposta é 503 e o log não traz dado pessoal")
    void shouldFailClosedWhenTheDatabaseFails() throws Exception {
        UUID id = UUID.randomUUID();
        CustomerRepository customers = mock(CustomerRepository.class);
        when(customers.findSessionVersion(id))
                .thenThrow(new DataAccessResourceFailureException("connection lost for " + EMAIL));
        var principal =
                new CustomerUserDetailsService.CustomerPrincipal(new CustomerCredentials(id, EMAIL, null, null, 0));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        Logger logger = (Logger) LoggerFactory.getLogger(SessionVersionFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            new SessionVersionFilter(customers, mock(AdministratorSessions.class))
                    .doFilter(new MockHttpServletRequest(), response, chain);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list)
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(EMAIL));
    }

    private static void authenticate(AdministratorPrincipal principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    @Test
    @DisplayName("SEC-006: se o banco falha ao conferir a sessão do Administrador, a resposta é 503")
    void shouldFailClosedForAnAdministratorWhenTheDatabaseFails() throws Exception {
        UUID id = UUID.randomUUID();
        AdministratorSessions sessions = mock(AdministratorSessions.class);
        when(sessions.findSessionVersion(id)).thenThrow(new DataAccessResourceFailureException("down for " + EMAIL));
        authenticate(new AdministratorPrincipal(id, EMAIL, 0, Set.of(Capability.PANEL_ACCESS)));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SessionVersionFilter(mock(CustomerRepository.class), sessions)
                .doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("SEC-006: sessão do Administrador com versão velha ou sem linha segue como anônima")
    void shouldEndAnAdministratorSessionWithAStaleVersionOrNoRow() throws Exception {
        UUID id = UUID.randomUUID();
        AdministratorSessions sessions = mock(AdministratorSessions.class);
        var principal = new AdministratorPrincipal(id, EMAIL, 1, Set.of(Capability.PANEL_ACCESS));
        for (OptionalInt stored : new OptionalInt[] {OptionalInt.of(2), OptionalInt.empty()}) {
            when(sessions.findSessionVersion(id)).thenReturn(stored);
            authenticate(principal);
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpSession session = (MockHttpSession) request.getSession();

            new SessionVersionFilter(mock(CustomerRepository.class), sessions)
                    .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(session.isInvalid()).isTrue();
        }
        when(sessions.findSessionVersion(id)).thenReturn(OptionalInt.of(1));
        authenticate(principal);

        new SessionVersionFilter(mock(CustomerRepository.class), sessions)
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }
}
