package br.com.sergio.sapiensia.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** SEC-007: the page that carries the reset token in its URL fragment must not leak it through the Referer. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ResetPasswordPageHeadersTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("SEC-007: /redefinir-senha is served with Referrer-Policy no-referrer")
    void resetPageHasNoReferrerPolicy() throws Exception {
        mvc.perform(get("/redefinir-senha"))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    @DisplayName("SEC-007: /painel/definir-senha is served with Referrer-Policy no-referrer")
    void adminSetupPageHasNoReferrerPolicy() throws Exception {
        mvc.perform(get("/painel/definir-senha"))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    @DisplayName("SEC-007: other app routes do not get the Referrer-Policy header")
    void otherRoutesAreUnchanged() throws Exception {
        mvc.perform(get("/entrar")).andExpect(status().isOk()).andExpect(header().doesNotExist("Referrer-Policy"));
    }

    @Test
    @DisplayName("SEC-007: the reset page keeps the self-only CSP, with no third-party source")
    void resetPageKeepsSelfOnlyCsp() throws Exception {
        mvc.perform(get("/redefinir-senha"))
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
                .andExpect(header().string("Content-Security-Policy", not(containsString("http"))));
    }
}
