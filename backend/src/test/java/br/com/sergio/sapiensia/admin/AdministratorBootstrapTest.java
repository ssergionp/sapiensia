package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminTestSupport.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.email.EmailMessage;
import br.com.sergio.sapiensia.security.Tokens;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(OutputCaptureExtension.class)
// Own context with its own configured e-mail, so e-mails of other test classes never reach these assertions.
@SpringBootTest(
        properties = {
            "test.isolated-context=admin-bootstrap",
            "app.admin.email=Admin-Boot@Example.com",
            "app.public-base-url=https://sapiensia.test"
        })
@Import(TestcontainersConfiguration.class)
class AdministratorBootstrapTest {

    private static final String EMAIL = "admin-boot@example.com";
    private static final String TASK = "administrator-password-setup";
    private static final String SUBJECT = "Defina sua senha de Administrador na SapiensIA";
    private static final Pattern LINK =
            Pattern.compile("^https://sapiensia\\.test/painel/definir-senha#([A-Za-z0-9_-]{43})$", Pattern.MULTILINE);
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Duration NO_MAIL_WAIT = Duration.ofSeconds(3);

    @Autowired
    private AdministratorBootstrap bootstrap;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecordingEmailSender sender;

    @BeforeEach
    void cleanState() throws Exception {
        // the startup runner also created the administrator and scheduled the e-mail: let it settle, drain it, reset
        eventually(
                WAIT,
                "the startup task to finish",
                () -> count("scheduled_tasks where task_name = '" + TASK + "'") == 0);
        while (sender.awaitMessageTo(EMAIL, Duration.ofMillis(200)).isPresent()) {
            // drain
        }
        jdbc.update("delete from administrators");
    }

    private int count(String fromAndWhere) {
        Integer n = jdbc.queryForObject("select count(*) from " + fromAndWhere, Integer.class);
        return n == null ? 0 : n;
    }

    private String tokenIn(EmailMessage message) {
        Matcher matcher = LINK.matcher(message.text());
        assertThat(matcher.find()).as("the e-mail has a set-password link").isTrue();
        return matcher.group(1);
    }

    private boolean isValid(String token) {
        return count("administrator_password_tokens where token_hash = '" + Tokens.hash(token)
                        + "' and used_at is null and expires_at > now()")
                == 1;
    }

    /** Creates the administrator, waits for the e-mail and for the link to be stored; returns the delivered token. */
    private String createAndDeliver() throws Exception {
        assertThat(bootstrap.bootstrap()).isEqualTo(BootstrapResult.CREATED);
        EmailMessage mail = sender.awaitMessageTo(EMAIL, WAIT).orElseThrow();
        eventually(
                WAIT,
                "the link to be stored",
                () -> count("administrators where password_link_sent_at is not null") == 1);
        // db-scheduler removes the finished instance a moment later: wait, or the next bootstrap() would find it
        eventually(
                WAIT, "the task to be removed", () -> count("scheduled_tasks where task_name = '" + TASK + "'") == 0);
        return tokenIn(mail);
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: banco vazio cria o Administrador sem senha e envia o link de primeiro acesso")
    void shouldCreateTheAdministratorAndSendTheLink(CapturedOutput output) throws Exception {
        assertThat(bootstrap.bootstrap()).isEqualTo(BootstrapResult.CREATED);

        EmailMessage mail = sender.awaitMessageTo(EMAIL, WAIT).orElseThrow();
        assertThat(mail.subject()).isEqualTo(SUBJECT);
        String token = tokenIn(mail);
        assertThat(mail.text())
                .contains("Uma conta de Administrador da SapiensIA foi criada para este e-mail.")
                .contains("O link vale por 24 horas e só pode ser usado uma vez.")
                .endsWith("Se você não esperava esta mensagem, não use o link.\n");
        assertThat(count("administrators")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select email from administrators", String.class))
                .isEqualTo(EMAIL);
        assertThat(jdbc.queryForObject("select password_hash from administrators", String.class))
                .isNull();
        eventually(WAIT, "the link to be stored", () -> isValid(token));
        assertThat(output).contains("administrator bootstrap: created");
    }

    @Test
    @DisplayName(
            "SEC-007 / ADR-0004: segunda subida logo depois não envia outro e-mail e o primeiro link continua válido")
    void shouldNotSendAgainRightAfterTheFirstEmail() throws Exception {
        String token = createAndDeliver();

        assertThat(bootstrap.bootstrap()).isEqualTo(BootstrapResult.PENDING_RECENTLY_SENT);

        assertThat(sender.awaitMessageTo(EMAIL, NO_MAIL_WAIT)).isEmpty();
        assertThat(isValid(token)).isTrue();
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: depois de mais de 1 hora sai um link novo e o anterior deixa de valer")
    void shouldSendANewLinkAfterTheMinimumInterval(CapturedOutput output) throws Exception {
        String firstToken = createAndDeliver();
        jdbc.update("update administrators set password_link_sent_at = now() - interval '61 minutes'");

        assertThat(bootstrap.bootstrap()).isEqualTo(BootstrapResult.PENDING_EMAIL_SCHEDULED);

        String secondToken = tokenIn(sender.awaitMessageTo(EMAIL, WAIT).orElseThrow());
        eventually(WAIT, "the new link to replace the old one", () -> isValid(secondToken));
        assertThat(output)
                .contains("administrator bootstrap: pending, e-mail scheduled")
                .doesNotContain(EMAIL)
                .doesNotContain(firstToken)
                .doesNotContain(secondToken);
        assertThat(secondToken).isNotEqualTo(firstToken);
        assertThat(isValid(firstToken)).isFalse();
        assertThat(count("administrator_password_tokens")).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: Administrador com senha definida não recebe e-mail na subida")
    void shouldDoNothingWhenThePasswordIsAlreadySet() throws Exception {
        createAndDeliver();
        jdbc.update("update administrators set password_hash = 'x'");

        assertThat(bootstrap.bootstrap()).isEqualTo(BootstrapResult.EXISTS);

        assertThat(sender.awaitMessageTo(EMAIL, NO_MAIL_WAIT)).isEmpty();
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: e-mail configurado diferente do gravado é ignorado")
    void shouldIgnoreAConfiguredEmailThatDiffersFromTheStoredOne() throws Exception {
        jdbc.update("insert into administrators (email) values ('stored@example.com')");

        assertThat(bootstrap.bootstrap()).isEqualTo(BootstrapResult.CONFIG_DIFFERS);

        assertThat(count("administrators")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select email from administrators", String.class))
                .isEqualTo("stored@example.com");
        assertThat(sender.awaitMessageTo(EMAIL, NO_MAIL_WAIT)).isEmpty();
        assertThat(sender.awaitMessageTo("stored@example.com", Duration.ofMillis(200)))
                .isEmpty();
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: duas subidas simultâneas deixam 1 Administrador e no máximo 1 tarefa pendente")
    void shouldCreateOneAdministratorWhenStartedTwiceAtTheSameTime() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<BootstrapResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return bootstrap.bootstrap();
                }));
            }
            go.countDown();
            List<BootstrapResult> results = new ArrayList<>();
            for (Future<BootstrapResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results).contains(BootstrapResult.CREATED);
            assertThat(count("administrators")).isEqualTo(1);
            assertThat(count("scheduled_tasks where task_name = '" + TASK + "'"))
                    .isLessThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(sender.awaitMessageTo(EMAIL, WAIT)).isPresent();
        assertThat(sender.awaitMessageTo(EMAIL, NO_MAIL_WAIT)).isEmpty();
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: tarefa pendente de um Administrador apagado entrega o link ao recriado")
    void shouldDeliverTheLinkToTheRecreatedAdministratorWhenATaskWasStillPending() throws Exception {
        sender.failNextSendTo(EMAIL);
        assertThat(bootstrap.bootstrap()).isEqualTo(BootstrapResult.CREATED);
        eventually(WAIT, "the simulated failure", () -> !sender.hasPendingFailureFor(EMAIL));
        // the failed task is now waiting for its retry; the row is deleted and recreated meanwhile (risk g)
        jdbc.update("delete from administrators");

        assertThat(bootstrap.bootstrap()).isEqualTo(BootstrapResult.CREATED);

        assertThat(sender.awaitMessageTo(EMAIL, WAIT)).isPresent();
        eventually(
                WAIT,
                "the link to be stored for the recreated administrator",
                () -> count("administrator_password_tokens t join administrators a on a.id = t.administrator_id"
                                + " where a.password_link_sent_at is not null")
                        == 1);
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: linha apagada é recriada e o link sai na hora (recuperação manual)")
    void shouldCreateAgainAndSendRightAwayAfterTheRowIsDeleted() throws Exception {
        createAndDeliver();
        jdbc.update("delete from administrators");

        assertThat(bootstrap.bootstrap()).isEqualTo(BootstrapResult.CREATED);

        assertThat(sender.awaitMessageTo(EMAIL, WAIT)).isPresent();
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: envio que falha não grava link e a retentativa grava só o link entregue")
    void shouldStoreNothingWhenTheSendFailsAndOnlyTheDeliveredLinkAfterTheRetry(CapturedOutput output)
            throws Exception {
        sender.failNextSendTo(EMAIL);

        assertThat(bootstrap.bootstrap()).isEqualTo(BootstrapResult.CREATED);

        eventually(WAIT, "the simulated failure", () -> !sender.hasPendingFailureFor(EMAIL));
        assertThat(count("administrator_password_tokens")).isZero();
        assertThat(count("administrators where password_link_sent_at is not null"))
                .isZero();

        String token = tokenIn(sender.awaitMessageTo(EMAIL, WAIT).orElseThrow());
        eventually(WAIT, "the delivered link to be stored", () -> count("administrator_password_tokens") == 1);
        assertThat(isValid(token)).isTrue();
        assertThat(count("administrators where password_link_sent_at is not null"))
                .isEqualTo(1);
        assertThat(output)
                .doesNotContain(EMAIL)
                .doesNotContain("Admin-Boot")
                .doesNotContain(token)
                .doesNotContain(Tokens.hash(token));
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: o log da subida não contém o e-mail nem o token")
    void shouldNotLogTheEmailOrTheToken(CapturedOutput output) throws Exception {
        String token = createAndDeliver();
        bootstrap.bootstrap(); // pending
        jdbc.update("update administrators set email = 'stored@example.com'");
        bootstrap.bootstrap(); // config differs
        jdbc.update("update administrators set email = '" + EMAIL + "', password_hash = 'x'");
        bootstrap.bootstrap(); // exists

        assertThat(output)
                .contains("administrator bootstrap: created")
                .contains("administrator bootstrap: pending")
                .contains("administrator bootstrap: configured e-mail differs from the existing administrator; ignored")
                .contains("administrator bootstrap: exists")
                .doesNotContain(EMAIL)
                .doesNotContain("Admin-Boot")
                .doesNotContain("stored@example.com")
                .doesNotContain(token)
                .doesNotContain(Tokens.hash(token));
    }
}
