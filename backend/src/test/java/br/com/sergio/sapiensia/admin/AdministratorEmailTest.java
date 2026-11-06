package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.email.EmailMessage;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        properties = {
            "test.isolated-context=admin-email",
            "app.admin.email=admin-mail@example.com",
            "app.public-base-url=https://sapiensia.test/"
        })
@Import(TestcontainersConfiguration.class)
class AdministratorEmailTest {

    private static final String TO = "mail-target@example.com";

    @Autowired
    private AdministratorEmailService emails;

    @Autowired
    private AdministratorPasswordTokens tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecordingEmailSender sender;

    private UUID administratorId;

    @BeforeEach
    void createAdministrator() throws Exception {
        // the startup runner scheduled a task that picks "the" administrator: let it finish before resetting
        AdminTestSupport.eventually(
                Duration.ofSeconds(15),
                "the startup task to finish",
                () -> jdbc.queryForObject(
                                "select count(*) from scheduled_tasks where task_name = 'administrator-password-setup'",
                                Integer.class)
                        == 0);
        jdbc.update("delete from administrators");
        administratorId =
                jdbc.queryForObject("insert into administrators (email) values (?) returning id", UUID.class, TO);
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: o e-mail usa só o app.public-base-url e a classe não recebe a requisição")
    void shouldBuildTheLinkFromTheConfiguredBaseUrlOnly() throws Exception {
        emails.sendPasswordSetup();

        EmailMessage mail = sender.awaitMessageTo(TO, Duration.ofSeconds(5)).orElseThrow();
        assertThat(mail.text())
                .containsPattern("\n\nhttps://sapiensia\\.test/painel/definir-senha#[A-Za-z0-9_-]{43}\n\n")
                // the default app.admin.password-link-ttl (PT24H) keeps the approved sentence
                .contains("O link vale por 24 horas e só pode ser usado uma vez.");
        assertThat(Arrays.stream(AdministratorEmailService.class.getDeclaredConstructors())
                        .flatMap(c -> Arrays.stream(c.getParameterTypes())))
                .doesNotContain(HttpServletRequest.class);
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: o link guardado é de uso único e define a senha só uma vez")
    void shouldLetTheStoredLinkSetThePasswordOnce() throws Exception {
        emails.sendPasswordSetup();
        EmailMessage mail = sender.awaitMessageTo(TO, Duration.ofSeconds(5)).orElseThrow();
        String token = mail.text().replaceAll("(?s).*#([A-Za-z0-9_-]{43}).*", "$1");

        assertThat(tokens.consumeAndSetPassword("not-" + token, "hash")).isEmpty();
        assertThat(tokens.consumeAndSetPassword(token, "hash-1")).contains(administratorId);
        assertThat(tokens.consumeAndSetPassword(token, "hash-2")).isEmpty();

        assertThat(jdbc.queryForObject(
                        "select password_hash from administrators where id = ?", String.class, administratorId))
                .isEqualTo("hash-1");
        assertThat(jdbc.queryForObject(
                        "select session_version from administrators where id = ?", Integer.class, administratorId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: Administrador que já tem senha não recebe link")
    void shouldNotSendWhenThePasswordIsAlreadySet() throws Exception {
        jdbc.update("update administrators set password_hash = 'x' where id = ?", administratorId);

        emails.sendPasswordSetup();

        assertThat(sender.awaitMessageTo(TO, Duration.ofSeconds(1))).isEmpty();
    }
}
