package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.email.EmailMessage;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ExtendWith(OutputCaptureExtension.class)
// Own context: a test that uses the csrf() shortcut replaces the CSRF repository of the shared context for good, and
// this class needs the real XSRF-TOKEN cookie.
@SpringBootTest(properties = "test.isolated-context=password-reset-request")
@Import(TestcontainersConfiguration.class)
class PasswordResetRequestTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Duration NO_MAIL_WAIT = Duration.ofSeconds(2);
    private static final String SUBJECT = "Redefinição de senha na SapiensIA";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecordingEmailSender sender;

    @Value("${app.public-base-url}")
    private String baseUrl;

    private MockMvc mvc;
    private Cookie csrfCookie;
    private String email;
    private UUID customerId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        csrfCookie = mvc.perform(get("/cadastro")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        email = "reset-" + UUID.randomUUID() + "@example.com";
        customerId = createCustomer(email, true);
    }

    private UUID createCustomer(String address, boolean confirmed) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at) values (?, ?, 'x', "
                        + (confirmed ? "now()" : "null") + ")",
                id,
                address);
        return id;
    }

    private MvcResult request(String address) throws Exception {
        return mvc.perform(post("/api/customers/password-resets")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\"}"))
                .andReturn();
    }

    private String tokenFrom(EmailMessage message) {
        Matcher matcher = Pattern.compile(Pattern.quote(baseUrl + "/redefinir-senha#") + "([A-Za-z0-9_-]{43})")
                .matcher(message.text());
        assertThat(matcher.find()).as("reset link in the e-mail").isTrue();
        return matcher.group(1);
    }

    private long rows() {
        return jdbc.queryForObject(
                "select count(*) from password_reset_tokens where customer_id = ?", Long.class, customerId);
    }

    private static List<String> headers(MvcResult result) {
        List<String> all = new ArrayList<>();
        for (String name : result.getResponse().getHeaderNames()) {
            all.add(name + "=" + result.getResponse().getHeaders(name));
        }
        Collections.sort(all);
        return all;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    @Test
    @DisplayName("SEC-007: e-mail confirmado recebe 202 vazio e um e-mail com o link")
    void shouldAcceptAndSendTheLinkToAConfirmedAccount() throws Exception {
        MvcResult result = request(email);

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        EmailMessage message = sender.awaitMessageTo(email, WAIT).orElseThrow();
        assertThat(message.subject()).isEqualTo(SUBJECT);
        String token = tokenFrom(message);
        assertThat(message.text()).contains("\n" + baseUrl + "/redefinir-senha#" + token + "\n");
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-007: e-mail inexistente recebe a mesma resposta e nenhum e-mail")
    void shouldAnswerTheSameForAnUnknownEmail() throws Exception {
        String unknown = "unknown-" + UUID.randomUUID() + "@example.com";

        MvcResult known = request(email);
        MvcResult missing = request(unknown);

        assertThat(missing.getResponse().getStatus())
                .isEqualTo(known.getResponse().getStatus());
        assertThat(missing.getResponse().getContentAsString())
                .isEqualTo(known.getResponse().getContentAsString());
        assertThat(headers(missing)).isEqualTo(headers(known));
        assertThat(sender.awaitMessageTo(unknown, NO_MAIL_WAIT)).isEmpty();
    }

    @Test
    @DisplayName("SEC-007: conta não confirmada recebe 202 e nenhum e-mail")
    void shouldAnswer202WithoutEmailForAnUnconfirmedAccount() throws Exception {
        String unconfirmed = "unconfirmed-" + UUID.randomUUID() + "@example.com";
        createCustomer(unconfirmed, false);

        assertThat(request(unconfirmed).getResponse().getStatus()).isEqualTo(202);

        assertThat(sender.awaitMessageTo(unconfirmed, NO_MAIL_WAIT)).isEmpty();
    }

    @Test
    @DisplayName("SEC-007: maiúsculas e espaços nas pontas encontram a conta")
    void shouldFindTheAccountIgnoringCaseAndSurroundingSpaces() throws Exception {
        assertThat(request("  " + email.toUpperCase() + " ").getResponse().getStatus())
                .isEqualTo(202);

        assertThat(sender.awaitMessageTo(email, WAIT)).isPresent();
    }

    @Test
    @DisplayName("SEC-007: dois pedidos seguidos deixam só o último link valendo")
    void shouldKeepOnlyTheLatestLinkAfterTwoRequests() throws Exception {
        request(email);
        String first = tokenFrom(sender.awaitMessageTo(email, WAIT).orElseThrow());
        request(email);
        String second = tokenFrom(sender.awaitMessageTo(email, WAIT).orElseThrow());

        assertThat(second).isNotEqualTo(first);
        assertThat(rows()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from password_reset_tokens where token_hash = ?",
                        Long.class,
                        EmailConfirmationTokens.hash(first)))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from password_reset_tokens where token_hash = ?",
                        Long.class,
                        EmailConfirmationTokens.hash(second)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-007: e-mail inválido recebe 400 EMAIL_INVALID")
    void shouldRejectAnInvalidEmail() throws Exception {
        for (String invalid : List.of("", "sem-arroba", "a".repeat(250) + "@e.com")) {
            mvc.perform(post("/api/customers/password-resets")
                            .cookie(csrfCookie)
                            .header("X-XSRF-TOKEN", csrfCookie.getValue())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + invalid + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.email").value("EMAIL_INVALID"));
        }
    }

    @Test
    @DisplayName("SEC-007: sem o token CSRF o pedido é recusado com 403")
    void shouldRejectARequestWithoutTheCsrfToken() throws Exception {
        mvc.perform(post("/api/customers/password-resets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-007: o tempo de resposta não revela se o e-mail está cadastrado")
    void shouldTakeTheSameTimeForKnownAndUnknownEmails() throws Exception {
        String unknown = "timing-" + UUID.randomUUID() + "@example.com";
        for (int i = 0; i < 5; i++) {
            assertThat(request(i % 2 == 0 ? email : unknown).getResponse().getStatus())
                    .isEqualTo(202);
        }
        List<Long> known = new ArrayList<>();
        List<Long> missing = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean useKnown = i % 2 == 0;
            long start = System.nanoTime();
            assertThat(request(useKnown ? email : unknown).getResponse().getStatus())
                    .isEqualTo(202);
            (useKnown ? known : missing).add((System.nanoTime() - start) / 1_000_000);
        }

        assertThat(Math.abs(median(known) - median(missing))).isLessThan(50);
    }

    @Test
    @DisplayName("SEC-007: nenhum log contém o token nem o e-mail")
    void shouldNotLogTheTokenOrTheEmail(CapturedOutput output) throws Exception {
        sender.failNextSendTo(email);

        request(email);
        request(email);
        String token = tokenFrom(sender.awaitMessageTo(email, WAIT).orElseThrow());

        assertThat(output.getAll()).doesNotContain(token).doesNotContain(email);
    }
}
