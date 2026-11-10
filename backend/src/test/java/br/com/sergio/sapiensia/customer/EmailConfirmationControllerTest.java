package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
// Own context: the csrf() shortcut replaces the CSRF repository of the shared context for good, and then the tests
// that need the real XSRF-TOKEN cookie (CustomerControllerTest) fail whenever this class runs before them.
@SpringBootTest(properties = "test.isolated-context=email-confirmation-controller")
class EmailConfirmationControllerTest {

    private static final String PASSWORD = "senhalongaok";
    private static final String URL = "/api/customers/email-confirmations";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CustomerService customerService;

    @Autowired
    EmailConfirmationTokens tokens;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private UUID newCustomer() {
        String email = "c" + UUID.randomUUID() + "@example.com";
        customerService.register(email, PASSWORD);
        return jdbc.queryForObject("select id from customers where email = ?", UUID.class, email);
    }

    private boolean confirmed(UUID id) {
        return jdbc.queryForObject(
                "select email_confirmed_at is not null from customers where id = ?", Boolean.class, id);
    }

    private ResultActions confirm(String body) throws Exception {
        return mvc.perform(
                post(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions confirm(String token, String password) throws Exception {
        return confirm("{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}");
    }

    @Test
    @DisplayName("SEC-007: o token e a senha certos confirmam o e-mail e respondem 204")
    void shouldConfirmWithCorrectTokenAndPassword() throws Exception {
        UUID id = newCustomer();
        String token = tokens.issue(id);

        confirm(token, PASSWORD).andExpect(status().isNoContent());

        assertThat(confirmed(id)).isTrue();
    }

    @Test
    @DisplayName("#49(a): senha errada responde 400 PASSWORD_INCORRECT e o link continua valendo")
    void shouldKeepLinkValidAfterWrongPassword() throws Exception {
        UUID id = newCustomer();
        String token = tokens.issue(id);

        confirm(token, "outrasenha123")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("PASSWORD_INCORRECT"));
        confirm(token, PASSWORD).andExpect(status().isNoContent());

        assertThat(confirmed(id)).isTrue();
    }

    @Test
    @DisplayName("#49: a 5ª senha errada apaga o link e responde TOKEN_LOCKED; depois disso o link é TOKEN_INVALID")
    void shouldDeleteLinkOnFifthWrongPassword() throws Exception {
        UUID id = newCustomer();
        String token = tokens.issue(id);

        for (int i = 0; i < 4; i++) {
            confirm(token, "outrasenha123")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.password").value("PASSWORD_INCORRECT"));
        }
        confirm(token, "outrasenha123")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.token").value("TOKEN_LOCKED"));
        confirm(token, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.token").value("TOKEN_INVALID"));

        assertThat(confirmed(id)).isFalse();
    }

    @Test
    @DisplayName("SEC-007: usar o mesmo token de novo responde 400 TOKEN_INVALID")
    void shouldRejectReusedToken() throws Exception {
        String token = tokens.issue(newCustomer());

        confirm(token, PASSWORD).andExpect(status().isNoContent());
        confirm(token, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.token").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("SEC-007: token vencido, desconhecido e ausente respondem igual")
    void shouldAnswerTheSameForExpiredUnknownAndMissingToken() throws Exception {
        UUID id = newCustomer();
        String expired = tokens.issue(id);
        jdbc.update(
                "update email_confirmation_tokens set expires_at = now() - interval '1 minute' where customer_id = ?",
                id);

        String[] bodies = {
            "{\"token\":\"" + expired + "\",\"password\":\"" + PASSWORD + "\"}",
            "{\"token\":\"desconhecido\",\"password\":\"" + PASSWORD + "\"}",
            "{\"password\":\"" + PASSWORD + "\"}",
            "{}",
            "{\"token\":\"\",\"password\":\"x\"}",
            "{\"token\":\"" + "a".repeat(65) + "\",\"password\":\"x\"}",
            "{\"token\":\"\",\"password\":\"\"}",
        };
        for (String body : bodies) {
            confirm(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.token").value("TOKEN_INVALID"));
        }
        confirm("{broken").andExpect(status().isBadRequest());
        assertThat(confirmed(id)).isFalse();
    }

    @Test
    void shouldRejectBlankAndTooLongPassword() throws Exception {
        UUID id = newCustomer();
        String token = tokens.issue(id);

        confirm(token, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("PASSWORD_REQUIRED"));
        confirm(token, "a".repeat(129))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("PASSWORD_INCORRECT"));

        assertThat(jdbc.queryForObject(
                        "select coalesce(sum(failed_attempts), 0) from email_confirmation_tokens where customer_id = ?",
                        Integer.class,
                        id))
                .isZero();
        confirm(token, PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("a POST sem token CSRF continua recusado")
    void shouldRejectPostWithoutCsrfToken() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
}
