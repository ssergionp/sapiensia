package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CustomerControllerTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    /** A new address per test: the sign-up limit counts the e-mail (SEC-014) and the tests repeat the same one. */
    String ana;

    /** A real token, obtained the way the SPA does: from the cookie of a first visit (not the csrf() test shortcut). */
    Cookie csrfCookie;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        ana = "ana-" + UUID.randomUUID() + "@example.com";
        jdbc.update("delete from customers");
        csrfCookie = mvc.perform(get("/cadastro")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private ResultActions register(String email, String password) throws Exception {
        return mvc.perform(post("/api/customers")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    @DisplayName("SEC-002: senha com 11 caracteres é recusada com PASSWORD_TOO_SHORT")
    void shouldRejectPasswordWithElevenCharacters() throws Exception {
        register(ana, "onzeletras1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("PASSWORD_TOO_SHORT"));
    }

    @Test
    @DisplayName("SEC-002: senha com 12 caracteres e sem símbolos é aceita")
    void shouldAcceptTwelveCharacterPasswordWithoutSymbols() throws Exception {
        register(ana, "senhalongaok").andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("SEC-002: senha vazada é recusada com PASSWORD_COMPROMISED")
    void shouldRejectCompromisedPassword() throws Exception {
        register(ana, "Password123456")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("PASSWORD_COMPROMISED"));
    }

    @Test
    @DisplayName("SEC-002: senha com acentos conta caracteres, não bytes")
    void shouldCountAccentedCharactersNotBytes() throws Exception {
        register(ana, "ááááááááááá")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("PASSWORD_TOO_SHORT"));
        register(ana, "áááááááááááá").andExpect(status().isAccepted());
    }

    @Test
    void shouldRejectPasswordLongerThan128Characters() throws Exception {
        register(ana, "a".repeat(129))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("PASSWORD_TOO_LONG"));
    }

    @Test
    @DisplayName("SEC-002: e-mail repetido responde igual ao e-mail novo")
    void shouldRespondTheSameForRepeatedEmail() throws Exception {
        MvcResult first = register(ana, "senhalongaok").andReturn();
        MvcResult second = register(ana, "senhalongaok").andReturn();

        assertThat(second.getResponse().getStatus())
                .isEqualTo(first.getResponse().getStatus());
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString())
                .isEmpty();
    }

    @Test
    void shouldRejectEmptyBodyWithCodesNotServerError() throws Exception {
        mvc.perform(post("/api/customers")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").value("EMAIL_INVALID"))
                .andExpect(jsonPath("$.errors.password").value("PASSWORD_REQUIRED"));

        mvc.perform(post("/api/customers")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest());

        register("a".repeat(243) + "@example.com", "senhalongaok")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").value("EMAIL_INVALID"));
    }

    @Test
    void shouldAllowAnonymousPostWithCsrfToken() throws Exception {
        register("bia@example.com", "senhalongaok").andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("e-mail com espaços e maiúsculas é tratado como o mesmo e-mail")
    void shouldTreatPaddedMixedCaseEmailAsTheSameEmail() throws Exception {
        register("  " + ana.toUpperCase() + " ", "senhalongaok").andExpect(status().isAccepted());
        register(ana, "senhalongaok").andExpect(status().isAccepted());

        assertThat(jdbc.queryForList("select email from customers", String.class))
                .containsExactly(ana);
    }

    @Test
    @DisplayName("a POST sem token CSRF continua recusado")
    void shouldRejectPostWithoutCsrfToken() throws Exception {
        mvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ana + "\",\"password\":\"senhalongaok\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a POST com o cookie XSRF-TOKEN e um cabeçalho X-XSRF-TOKEN diferente é recusado")
    void shouldRejectPostWithMismatchedCsrfHeader() throws Exception {
        mvc.perform(post("/api/customers")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", "not-the-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ana + "\",\"password\":\"senhalongaok\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("o cookie XSRF-TOKEN chega na primeira visita à tela de cadastro")
    void shouldSetXsrfCookieOnFirstVisitToSignupPage() throws Exception {
        mvc.perform(get("/cadastro")).andExpect(status().isOk()).andExpect(cookie().exists("XSRF-TOKEN"));
    }
}
