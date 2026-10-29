package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EmailConfirmationTest {

    private static final String PASSWORD = "uma senha longa 1";
    private static final String WRONG_PASSWORD = "senha errada 123";

    @Autowired
    private CustomerService customerService;

    @Autowired
    private EmailConfirmationTokens tokens;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RecordingEmailSender sender;

    private String email;
    private UUID customerId;

    @BeforeEach
    void createCustomer() throws InterruptedException {
        email = "confirm-" + UUID.randomUUID() + "@example.com";
        customerService.register(email, PASSWORD);
        customerId = jdbc.queryForObject("select id from customers where email = ?", UUID.class, email);
        // registration issues a link in the background: wait for it and drop it, so each test starts with no link
        assertThat(sender.awaitMessageTo(email, Duration.ofSeconds(15))).isPresent();
        tokens.revokeAll(customerId);
    }

    private Object confirmedAt() {
        return jdbc.queryForObject("select email_confirmed_at from customers where id = ?", Object.class, customerId);
    }

    private long tokenRows() {
        return jdbc.queryForObject(
                "select count(*) from email_confirmation_tokens where customer_id = ?", Long.class, customerId);
    }

    @Test
    @DisplayName("SEC-007: o token é aleatório e o banco guarda só o hash")
    void shouldGenerateRandomTokensAndStoreOnlyTheirHash() {
        String first = tokens.issue(customerId);
        String second = tokens.issue(customerId);

        assertThat(first).isNotEqualTo(second);
        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(second).matches("[A-Za-z0-9_-]{43}");

        List<String> hashes = jdbc.queryForList(
                "select token_hash from email_confirmation_tokens where customer_id = ?", String.class, customerId);
        assertThat(hashes).hasSize(2).allSatisfy(hash -> assertThat(hash).matches("[0-9a-f]{64}"));

        String wholeTable = jdbc.queryForObject(
                "select coalesce(string_agg(t::text, ' '), '') from email_confirmation_tokens t", String.class);
        assertThat(wholeTable).doesNotContain(first).doesNotContain(second);
    }

    @Test
    @DisplayName("SEC-007: o link confirma o e-mail uma única vez")
    void shouldConfirmEmailOnlyOnce() {
        String token = tokens.issue(customerId);

        customerService.confirmEmail(token, PASSWORD);
        Object firstConfirmation = confirmedAt();

        assertThat(firstConfirmation).isNotNull();
        assertThatThrownBy(() -> customerService.confirmEmail(token, PASSWORD))
                .isInstanceOf(InvalidConfirmationTokenException.class);
        assertThat(confirmedAt()).isEqualTo(firstConfirmation);
    }

    @Test
    @DisplayName("#49: sem a senha certa o e-mail não é confirmado, e o link continua valendo")
    void shouldNotConfirmWithWrongPasswordAndKeepLinkValid() {
        String token = tokens.issue(customerId);

        assertThatThrownBy(() -> customerService.confirmEmail(token, WRONG_PASSWORD))
                .isInstanceOf(IncorrectConfirmationPasswordException.class);

        assertThat(confirmedAt()).isNull();
        assertThat(tokenRows()).isEqualTo(1);
        customerService.confirmEmail(token, PASSWORD);
        assertThat(confirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("SEC-007: token vencido é recusado")
    void shouldRejectExpiredToken() {
        String token = tokens.issue(customerId);
        jdbc.update("update email_confirmation_tokens set expires_at = now() - interval '1 second'");

        assertThatThrownBy(() -> customerService.confirmEmail(token, PASSWORD))
                .isInstanceOf(InvalidConfirmationTokenException.class);
        assertThat(confirmedAt()).isNull();
    }

    @Test
    @DisplayName("SEC-007: token desconhecido, vazio ou enorme é recusado do mesmo jeito")
    void shouldRejectUnknownEmptyOrHugeTokenTheSameWay() {
        for (String token : List.of("", "x", "a".repeat(10_000))) {
            assertThatThrownBy(() -> customerService.confirmEmail(token, PASSWORD))
                    .isExactlyInstanceOf(InvalidConfirmationTokenException.class);
        }
    }

    @Test
    void shouldAllowOnlyOneWinnerWhenSameTokenIsConfirmedConcurrently() throws Exception {
        String token = tokens.issue(customerId);
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Throwable>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        customerService.confirmEmail(token, PASSWORD);
                        return null;
                    } catch (Throwable e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int winners = 0;
            for (Future<Throwable> result : results) {
                Throwable failure = result.get(60, TimeUnit.SECONDS);
                if (failure == null) {
                    winners++;
                } else {
                    assertThat(failure).isInstanceOf(InvalidConfirmationTokenException.class);
                }
            }
            assertThat(winners).isEqualTo(1);
            assertThat(confirmedAt()).isNotNull();
            assertThat(tokenRows()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("#49: palpites errados em paralelo não passam do limite de 5 por link")
    void shouldInvalidateLinkAfterConcurrentWrongPasswords() throws Exception {
        String token = tokens.issue(customerId);
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Throwable>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        customerService.confirmEmail(token, WRONG_PASSWORD);
                        return null;
                    } catch (Throwable e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<Throwable> result : results) {
                assertThat(result.get(60, TimeUnit.SECONDS))
                        .isInstanceOfAny(
                                IncorrectConfirmationPasswordException.class, InvalidConfirmationTokenException.class);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(tokenRows()).isZero();
        assertThatThrownBy(() -> customerService.confirmEmail(token, PASSWORD))
                .isInstanceOf(InvalidConfirmationTokenException.class);
        assertThat(confirmedAt()).isNull();
    }

    @Test
    @DisplayName("#49: link com 5 erros registrados não vale mais, mesmo que ainda não tenha sido apagado")
    void shouldRejectLinkAtTheLimitEvenIfItWasNotDeleted() {
        String token = tokens.issue(customerId);
        jdbc.update("update email_confirmation_tokens set failed_attempts = 5 where customer_id = ?", customerId);

        assertThatThrownBy(() -> customerService.confirmEmail(token, PASSWORD))
                .isInstanceOf(InvalidConfirmationTokenException.class);
        assertThat(confirmedAt()).isNull();
    }

    @Test
    @DisplayName("#49: depois de 5 senhas erradas o link deixa de valer")
    void shouldInvalidateLinkAfterFiveWrongPasswords() {
        String token = tokens.issue(customerId);

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> customerService.confirmEmail(token, WRONG_PASSWORD))
                    .isInstanceOf(IncorrectConfirmationPasswordException.class);
        }
        assertThatThrownBy(() -> customerService.confirmEmail(token, WRONG_PASSWORD))
                .isInstanceOf(InvalidConfirmationTokenException.class);

        assertThat(tokenRows()).isZero();
        assertThatThrownBy(() -> customerService.confirmEmail(token, PASSWORD))
                .isInstanceOf(InvalidConfirmationTokenException.class);
        assertThat(confirmedAt()).isNull();
    }

    @Test
    void shouldConfirmWithCorrectPasswordAfterFourWrongOnes() {
        String token = tokens.issue(customerId);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> customerService.confirmEmail(token, WRONG_PASSWORD))
                    .isInstanceOf(IncorrectConfirmationPasswordException.class);
        }

        assertThatCode(() -> customerService.confirmEmail(token, PASSWORD)).doesNotThrowAnyException();
        assertThat(confirmedAt()).isNotNull();
    }

    @Test
    void shouldCountWrongPasswordsPerLink() {
        String first = tokens.issue(customerId);
        String second = tokens.issue(customerId);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> customerService.confirmEmail(first, WRONG_PASSWORD))
                    .isInstanceOf(IncorrectConfirmationPasswordException.class);
        }

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> customerService.confirmEmail(second, WRONG_PASSWORD))
                    .isInstanceOf(IncorrectConfirmationPasswordException.class);
        }
        customerService.confirmEmail(second, PASSWORD);
        assertThat(confirmedAt()).isNotNull();
    }

    @Test
    void shouldRevokeAllTokensOfACustomer() {
        String first = tokens.issue(customerId);
        String second = tokens.issue(customerId);

        tokens.revokeAll(customerId);

        assertThat(tokenRows()).isZero();
        assertThatThrownBy(() -> customerService.confirmEmail(first, PASSWORD))
                .isInstanceOf(InvalidConfirmationTokenException.class);
        assertThatThrownBy(() -> customerService.confirmEmail(second, PASSWORD))
                .isInstanceOf(InvalidConfirmationTokenException.class);
    }

    @Test
    void shouldDeleteTokensWhenCustomerIsDeleted() {
        tokens.issue(customerId);
        assertThat(tokenRows()).isEqualTo(1);

        jdbc.update("delete from customers where id = ?", customerId);

        assertThat(tokenRows()).isZero();
    }

    @Test
    @DisplayName("SEC-033: a tabela de tokens só guarda o necessário")
    void shouldStoreOnlyWhatIsNeeded() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'email_confirmation_tokens'",
                String.class);

        assertThat(columns)
                .containsExactlyInAnyOrder(
                        "id", "customer_id", "token_hash", "expires_at", "failed_attempts", "created_at");
    }
}
