package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.email.EmailMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CustomerEmailTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String PASSWORD = "uma senha longa 1";
    private static final String OTHER_PASSWORD = "outra senha longa 2";
    private static final String CONFIRMATION_SUBJECT = "Confirme seu e-mail na SapiensIA";
    private static final String NOTICE_SUBJECT = "Tentativa de cadastro na SapiensIA";

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerEmailService customerEmailService;

    @Autowired
    private RecordingEmailSender sender;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${app.public-base-url}")
    private String baseUrl;

    private String email;

    @BeforeEach
    void uniqueEmail() {
        email = "email-" + UUID.randomUUID() + "@example.com";
    }

    private EmailMessage nextMessageTo(String to) throws InterruptedException {
        return sender.awaitMessageTo(to, WAIT).orElseThrow(() -> new AssertionError("no e-mail arrived"));
    }

    private String tokenFrom(EmailMessage message) {
        Matcher matcher = Pattern.compile(Pattern.quote(baseUrl + "/confirmar-email#") + "([A-Za-z0-9_-]{43})")
                .matcher(message.text());
        assertThat(matcher.find()).as("confirmation link in the e-mail").isTrue();
        return matcher.group(1);
    }

    private String passwordHash(String address) {
        return jdbc.queryForObject("select password_hash from customers where email = ?", String.class, address);
    }

    private Object confirmedAt(String address) {
        return jdbc.queryForObject("select email_confirmed_at from customers where email = ?", Object.class, address);
    }

    @Test
    @DisplayName("SEC-007: cadastro novo recebe o e-mail com o link de confirmação")
    void shouldSendConfirmationLinkToNewCustomer() throws Exception {
        customerService.register(email, PASSWORD);

        EmailMessage message = nextMessageTo(email);
        assertThat(message.subject()).isEqualTo(CONFIRMATION_SUBJECT);
        String token = tokenFrom(message);

        customerService.confirmEmail(token, PASSWORD);
        assertThat(confirmedAt(email)).isNotNull();
    }

    @Test
    @DisplayName("SEC-007: e-mail repetido de conta confirmada recebe o aviso, sem token e sem dados da conta")
    void shouldSendNoticeWithoutTokenWhenConfirmedEmailRegistersAgain() throws Exception {
        customerService.register(email, PASSWORD);
        customerService.confirmEmail(tokenFrom(nextMessageTo(email)), PASSWORD);
        String hashBefore = passwordHash(email);
        Object confirmedBefore = confirmedAt(email);

        customerService.register(email, OTHER_PASSWORD);

        EmailMessage notice = nextMessageTo(email);
        assertThat(notice.subject()).isEqualTo(NOTICE_SUBJECT);
        assertThat(notice.text())
                .contains(baseUrl + "/entrar")
                .contains(baseUrl + "/recuperar-senha")
                .doesNotContain("confirmar-email")
                .doesNotContain(OTHER_PASSWORD)
                .doesNotContain(hashBefore);
        assertThat(passwordHash(email)).isEqualTo(hashBefore);
        assertThat(confirmedAt(email)).isEqualTo(confirmedBefore);
    }

    @Test
    @DisplayName("#49(b): cadastro repetido de e-mail não confirmado troca a senha e invalida o link anterior")
    void shouldReplacePasswordAndRevokeOldLinkWhenUnconfirmedEmailRegistersAgain() throws Exception {
        customerService.register(email, PASSWORD);
        String firstToken = tokenFrom(nextMessageTo(email));

        customerService.register(email, OTHER_PASSWORD);
        String secondToken = tokenFrom(nextMessageTo(email));

        assertThatThrownBy(() -> customerService.confirmEmail(firstToken, PASSWORD))
                .isInstanceOf(InvalidConfirmationTokenException.class);
        assertThatThrownBy(() -> customerService.confirmEmail(firstToken, OTHER_PASSWORD))
                .isInstanceOf(InvalidConfirmationTokenException.class);
        assertThatThrownBy(() -> customerService.confirmEmail(secondToken, PASSWORD))
                .isInstanceOf(IncorrectConfirmationPasswordException.class);
        customerService.confirmEmail(secondToken, OTHER_PASSWORD);
        assertThat(confirmedAt(email)).isNotNull();
    }

    @Test
    @DisplayName("#49: o atacante que se cadastrou primeiro não consegue confirmar a conta da vítima")
    void shouldNotLetFirstRegistrantConfirmTheVictimsAccount() throws Exception {
        String victim = "vitima-" + UUID.randomUUID() + "@example.com";
        String attackerPassword = "senha do atacante 1";
        String victimPassword = "senha da vitima 2";

        customerService.register(victim, attackerPassword);
        nextMessageTo(victim); // lands in the victim's inbox, not the attacker's
        customerService.register(victim, victimPassword);
        String token = tokenFrom(nextMessageTo(victim));

        assertThatThrownBy(() -> customerService.confirmEmail(token, attackerPassword))
                .isInstanceOf(IncorrectConfirmationPasswordException.class);
        customerService.confirmEmail(token, victimPassword);
        assertThat(confirmedAt(victim)).isNotNull();
        String hash = passwordHash(victim);
        assertThat(passwordEncoder.matches(victimPassword, hash)).isTrue();
        assertThat(passwordEncoder.matches(attackerPassword, hash)).isFalse();
    }

    @Test
    @DisplayName("SEC-007: cadastro novo e cadastro repetido geram um e-mail cada, nem mais nem menos")
    void shouldSendExactlyOneEmailPerRegistration() throws Exception {
        customerService.register(email, PASSWORD);
        customerService.register(email, OTHER_PASSWORD);

        nextMessageTo(email);
        nextMessageTo(email);
        assertThat(sender.awaitMessageTo(email, Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void shouldSendToNormalizedAddress() throws Exception {
        String id = UUID.randomUUID().toString();

        customerService.register("  Ana-" + id + "@Example.COM ", PASSWORD);

        assertThat(nextMessageTo("ana-" + id + "@example.com").subject()).isEqualTo(CONFIRMATION_SUBJECT);
    }

    @Test
    void shouldNotSendConfirmationWhenEmailAlreadyConfirmed() throws Exception {
        customerService.register(email, PASSWORD);
        customerService.confirmEmail(tokenFrom(nextMessageTo(email)), PASSWORD);
        UUID customerId = jdbc.queryForObject("select id from customers where email = ?", UUID.class, email);

        customerEmailService.sendConfirmation(customerId);

        assertThat(sender.awaitMessageTo(email, Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("SEC-007: nenhum log traz o e-mail, o token ou a senha")
    void shouldNotLogEmailTokenOrPassword(CapturedOutput output) throws Exception {
        sender.failNextSendTo(email);

        customerService.register(email, PASSWORD);
        String token = tokenFrom(nextMessageTo(email));
        customerService.confirmEmail(token, PASSWORD);

        assertThat(sender.hasPendingFailureFor(email)).isFalse();
        assertThat(output.getAll())
                .doesNotContain(email)
                .doesNotContain(token)
                .doesNotContain(PASSWORD)
                .doesNotContain(passwordHash(email));
    }

    @Test
    void shouldRetryWhenSenderFails() throws Exception {
        sender.failNextSendTo(email);

        customerService.register(email, PASSWORD);

        assertThat(nextMessageTo(email).subject()).isEqualTo(CONFIRMATION_SUBJECT);
        assertThat(sender.hasPendingFailureFor(email))
                .as("the first attempt failed")
                .isFalse();
    }

    @Test
    void shouldKeepOneAccountAndSendOnlyConfirmationsWhenSameNewEmailRegistersConcurrently() throws Exception {
        int threads = 8;
        List<String> passwords = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            passwords.add("uma senha longa " + i);
        }
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String password : passwords) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    customerService.register(email, password);
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS); // rethrows if any register threw
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select count(*) from customers where email = ?", Long.class, email))
                .isEqualTo(1);
        for (int i = 0; i < threads; i++) {
            assertThat(nextMessageTo(email).subject()).isEqualTo(CONFIRMATION_SUBJECT);
        }
        assertThat(sender.awaitMessageTo(email, Duration.ofSeconds(3))).isEmpty();
        String hash = passwordHash(email);
        assertThat(passwords).anyMatch(password -> passwordEncoder.matches(password, hash));
    }
}
