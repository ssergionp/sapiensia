package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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
            new SessionVersionFilter(customers).doFilter(new MockHttpServletRequest(), response, chain);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list)
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(EMAIL));
    }
}
