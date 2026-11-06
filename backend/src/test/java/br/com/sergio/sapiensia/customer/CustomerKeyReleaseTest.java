package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// Own context: the limiter state is in memory. The lock is made through the login with another spelling of the e-mail,
// and released by the reset, which reads the e-mail from the database: both must build the same customerKey.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "test.isolated-context=customer-key-release")
class CustomerKeyReleaseTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String OLD_PASSWORD = "senha-antiga-123";
    private static final String NEW_PASSWORD = "senha-nova-4567"; // gitleaks:allow
    private static final String WRONG = "senhaerrada123";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RecordingEmailSender sender;

    @Value("${app.public-base-url}")
    private String baseUrl;

    private MockMvc mvc;
    private Cookie csrfCookie;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        csrfCookie = mvc.perform(get("/entrar")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private int send(String path, String json) throws Exception {
        return mvc.perform(post(path)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int login(String email, String password) throws Exception {
        return send("/api/sessions", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private String resetToken(String email) throws Exception {
        assertThat(send("/api/customers/password-resets", "{\"email\":\"" + email + "\"}"))
                .isEqualTo(202);
        String text = sender.awaitMessageTo(email, WAIT).orElseThrow().text();
        Matcher matcher = Pattern.compile(Pattern.quote(baseUrl + "/redefinir-senha#") + "([A-Za-z0-9_-]{43})")
                .matcher(text);
        assertThat(matcher.find()).as("reset link in the e-mail").isTrue();
        return matcher.group(1);
    }

    @Test
    @DisplayName(
            "SEC-002: o bloqueio criado pelo login com o e-mail em maiúsculas e com espaços é liberado pela redefinição")
    void shouldReleaseALockMadeWithAnotherSpellingOfTheEmail() throws Exception {
        String email = "chave-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at) values (gen_random_uuid(), ?, ?,"
                        + " now())",
                email,
                passwordEncoder.encode(OLD_PASSWORD));
        String spelling = "  " + email.toUpperCase(Locale.ROOT) + " ";
        for (int i = 0; i < 5; i++) {
            assertThat(login(spelling, WRONG)).isEqualTo(401);
        }
        assertThat(login(email, OLD_PASSWORD)).as("locked for every spelling").isEqualTo(429);

        String token = resetToken(email);
        assertThat(send(
                        "/api/customers/password-resets/confirmation",
                        "{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .isEqualTo(204);

        assertThat(login(spelling, NEW_PASSWORD)).isEqualTo(204);
    }
}
