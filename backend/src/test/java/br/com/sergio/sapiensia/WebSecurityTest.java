package br.com.sergio.sapiensia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.sergio.sapiensia.customer.SessionVersionFilter;
import br.com.sergio.sapiensia.web.RequestBodyLimitFilter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The security baseline and the SPA routing that every project inherits from the template. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WebSecurityTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    SecurityFilterChain chain;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName(
            "SEC-006 SEC-017: o limite de corpo e a conferência da sessão rodam depois dos cabeçalhos e antes do CSRF")
    void shouldRunTheBodyLimitAndTheSessionCheckAfterTheHeadersAndBeforeCsrf() {
        List<Class<?>> order =
                chain.getFilters().stream().<Class<?>>map(Object::getClass).toList();

        int headers = order.indexOf(HeaderWriterFilter.class);
        int bodyLimit = order.indexOf(RequestBodyLimitFilter.class);
        int sessionVersion = order.indexOf(SessionVersionFilter.class);
        int csrf = order.indexOf(CsrfFilter.class);

        assertThat(headers).isNotNegative();
        assertThat(bodyLimit).isGreaterThan(headers);
        assertThat(sessionVersion).isGreaterThan(bodyLimit);
        assertThat(csrf).isGreaterThan(sessionVersion);
    }

    @Test
    @DisplayName("health check is public and reports UP without details")
    void healthIsPublic() throws Exception {
        mvc.perform(get("/api/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("an API route the project did not open is denied (401 for an anonymous caller)")
    void apiIsDeniedByDefault() throws Exception {
        mvc.perform(get("/api/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a POST without the CSRF token is rejected")
    void postWithoutCsrfIsRejected() throws Exception {
        mvc.perform(post("/api/system/health")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an app route such as /pedidos/42 gets the React index.html")
    void appRoutesGetTheSpa() throws Exception {
        mvc.perform(get("/pedidos/42"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("spa-index-for-tests")));
    }

    @Test
    @DisplayName("a missing file is 404, not the React page")
    void missingFileIsNotFound() throws Exception {
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("pages carry the security headers")
    void securityHeaders() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }
}
