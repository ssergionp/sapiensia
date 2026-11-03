package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
// The property only gives this class its own Spring context: tests that use the csrf() shortcut replace the CSRF
// token repository of the filter chain in a shared context, and then no XSRF-TOKEN cookie is issued any more.
@SpringBootTest(properties = "test.isolated-context=session-controller")
class SessionControllerTest {

    private static final String PASSWORD = "senhalongaok";
    private static final String SESSIONS = "/api/sessions";
    private static final String CURRENT = "/api/sessions/current";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    MockMvc mvc;

    /** A real token, obtained the way the SPA does: from the cookie of a first visit. */
    Cookie csrfCookie;

    String confirmedEmail;
    String unconfirmedEmail;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.update("delete from customers");
        confirmedEmail = newCustomer(true);
        unconfirmedEmail = newCustomer(false);
        csrfCookie = mvc.perform(get("/entrar")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private String newCustomer(boolean confirmed) {
        String email = "c" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at)"
                        + " values (gen_random_uuid(), ?, ?, case when ? then now() end)",
                email,
                passwordEncoder.encode(PASSWORD),
                confirmed);
        return email;
    }

    private MvcResult login(String email, String password, MockHttpSession session) throws Exception {
        var request = post(SESSIONS)
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        if (session != null) {
            // like a browser that sends the session cookie: MockMvc does not mark the id as requested by itself
            request.session(session).with(r -> {
                r.setRequestedSessionId(session.getId());
                return r;
            });
        }
        return mvc.perform(request).andReturn();
    }

    /** The login first expires the old XSRF-TOKEN cookie and then sets the new one: the last one counts. */
    private static Cookie lastCsrfCookie(MvcResult result) {
        Cookie last = null;
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (cookie.getName().equals("XSRF-TOKEN")) {
                last = cookie;
            }
        }
        return last;
    }

    private MvcResult login(String email, String password) throws Exception {
        return login(email, password, null);
    }

    @Test
    @DisplayName("SEC-004: cliente confirmado entra, recebe 204 e uma sessão autenticada")
    void shouldLoginConfirmedCustomerAndOpenSession() throws Exception {
        MvcResult result = login(confirmedEmail, PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        mvc.perform(get(CURRENT).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(confirmedEmail));
    }

    @Test
    @DisplayName(
            "SEC-004: senha errada, e-mail desconhecido e conta não confirmada respondem igual, sem sessão nem cookie")
    void shouldRejectWrongPasswordUnknownEmailAndUnconfirmedAccountWithTheSameResponse() throws Exception {
        MvcResult wrongPassword = login(confirmedEmail, "senhaerrada123");
        MvcResult unknownEmail = login("ninguem@example.com", PASSWORD);
        MvcResult unconfirmed = login(unconfirmedEmail, PASSWORD);

        for (MvcResult result : new MvcResult[] {wrongPassword, unknownEmail, unconfirmed}) {
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getContentAsString())
                    .isEqualTo(wrongPassword.getResponse().getContentAsString());
            assertThat(result.getResponse().getHeaderNames()).doesNotContain("Set-Cookie");
            assertThat(result.getRequest().getSession(false)).isNull();
        }
        assertThat(wrongPassword.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    @DisplayName("SEC-004: conta não confirmada é recusada mesmo com a senha certa")
    void shouldRejectUnconfirmedCustomerEvenWithTheCorrectPassword() throws Exception {
        MvcResult result = login(unconfirmedEmail, PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-004: e-mail e campos vazios respondem 401 igual")
    void shouldAnswer401ForMissingFields() throws Exception {
        for (String body : new String[] {"{}", "{\"email\":\"\",\"password\":\"\"}", "{\"email\":null}"}) {
            mvc.perform(post(SESSIONS)
                            .cookie(csrfCookie)
                            .header("X-XSRF-TOKEN", csrfCookie.getValue())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("SEC-004: o e-mail digitado com espaços e maiúsculas entra na mesma conta")
    void shouldLoginIgnoringCaseAndSurroundingSpaces() throws Exception {
        MvcResult result = login("  " + confirmedEmail.toUpperCase() + " ", PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("SEC-006: depois do logout o cookie antigo é recusado")
    void shouldRefuseOldSessionAfterLogout() throws Exception {
        MvcResult loggedIn = login(confirmedEmail, PASSWORD);
        MockHttpSession session = (MockHttpSession) loggedIn.getRequest().getSession(false);
        Cookie newCsrf = lastCsrfCookie(loggedIn);

        mvc.perform(delete(CURRENT).session(session).cookie(newCsrf).header("X-XSRF-TOKEN", newCsrf.getValue()))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get(CURRENT).session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEC-004: o login troca o id da sessão e o token CSRF (fixação de sessão)")
    void shouldChangeSessionIdAndCsrfTokenOnLogin() throws Exception {
        MockHttpSession before = new MockHttpSession();
        String oldId = before.getId();

        MvcResult result = login(confirmedEmail, PASSWORD, before);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getRequest().getSession(false).getId()).isNotEqualTo(oldId);
        Cookie newCsrf = lastCsrfCookie(result);
        assertThat(newCsrf).isNotNull();
        assertThat(newCsrf.getValue()).isNotBlank();
        assertThat(newCsrf.getValue()).isNotEqualTo(csrfCookie.getValue());
    }

    @Test
    @DisplayName("o login sem token CSRF é recusado com 403")
    void shouldRejectLoginWithoutCsrfToken() throws Exception {
        mvc.perform(post(SESSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + confirmedEmail + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("quem não entrou recebe 401 em GET /api/sessions/current")
    void shouldAnswer401ForCurrentSessionWithoutLogin() throws Exception {
        mvc.perform(get(CURRENT)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rota de /api não liberada responde 401 para anônimo")
    void shouldAnswer401ForAnonymousOnProtectedRoute() throws Exception {
        mvc.perform(get("/api/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SEC-004: o login com falha não registra senha nem e-mail no log")
    void shouldNotLogPasswordOrEmail() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        String secret = "segredoQueNaoPodeVazar";
        try {
            login(confirmedEmail, secret);
            login("desconhecido.log@example.com", secret);
        } finally {
            root.detachAppender(appender);
        }

        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage() + " " + event.getThrowableProxy();
            assertThat(text)
                    .doesNotContain(secret)
                    .doesNotContain(confirmedEmail)
                    .doesNotContain("desconhecido.log");
        }
    }

    @Test
    @DisplayName("SEC-004: a sessão guarda um principal sem o hash da senha e sem e-mail no toString")
    void shouldKeepPasswordHashAndEmailOutOfTheSessionPrincipal() throws Exception {
        String hash = jdbc.queryForObject(
                "select password_hash from customers where email = ?", String.class, confirmedEmail);
        Logger root = (Logger) LoggerFactory.getLogger("org.springframework.security");
        ch.qos.logback.classic.Level previous = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(ch.qos.logback.classic.Level.TRACE);
        MvcResult result;
        try {
            result = login(confirmedEmail, PASSWORD);
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
        }

        var securityContext = (org.springframework.security.core.context.SecurityContext)
                result.getRequest().getSession(false).getAttribute("SPRING_SECURITY_CONTEXT");
        var authentication = securityContext.getAuthentication();
        var principal = (org.springframework.security.core.userdetails.UserDetails) authentication.getPrincipal();
        assertThat(principal.getPassword()).isNull();
        for (String text : new String[] {principal.toString(), authentication.toString()}) {
            assertThat(text).doesNotContain(hash).doesNotContain(confirmedEmail);
        }
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(hash).doesNotContain(confirmedEmail);
        }
    }

    @Test
    @DisplayName("o app define o próprio UserDetailsService, então o Spring não gera senha de usuário")
    void shouldDefineOnlyTheCustomerUserDetailsService() {
        assertThat(context.getBeansOfType(UserDetailsService.class).values())
                .hasSize(1)
                .allSatisfy(bean -> assertThat(bean).isInstanceOf(CustomerUserDetailsService.class));
    }
}
