package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CustomerServiceTest {

    private static final String PASSWORD = "uma senha longa 1";

    @Autowired
    private CustomerService customerService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTable() {
        jdbc.update("delete from customers");
    }

    private long countCustomers() {
        return jdbc.queryForObject("select count(*) from customers", Long.class);
    }

    @Test
    void shouldFindEmailOfExistingCustomerAndNothingForUnknownId() {
        customerService.register("ana@example.com", PASSWORD);
        java.util.UUID id = jdbc.queryForObject("select id from customers", java.util.UUID.class);

        assertThat(customerService.findEmail(id)).contains("ana@example.com");
        assertThat(customerService.findEmail(java.util.UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("SEC-003: o banco guarda Argon2id, nunca a senha")
    void shouldStoreArgon2idHashInsteadOfPassword() {
        customerService.register("ana@example.com", PASSWORD);

        String hash = jdbc.queryForObject("select password_hash from customers", String.class);
        assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
    }

    @Test
    @DisplayName("SEC-002: senha vazada é recusada e nada é gravado")
    void shouldRejectCompromisedPasswordAndStoreNothing() {
        assertThatThrownBy(() -> customerService.register("ana@example.com", "Password123456"))
                .isInstanceOf(PasswordCompromisedException.class);

        assertThat(countCustomers()).isZero();
    }

    @Test
    @DisplayName("SEC-033: a tabela só guarda o necessário")
    void shouldKeepOnlyNecessaryColumnsInCustomersTable() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns"
                        + " where table_schema = current_schema() and table_name = 'customers'",
                String.class);

        assertThat(columns)
                .containsExactlyInAnyOrder(
                        "id",
                        "email",
                        "password_hash",
                        "email_confirmed_at",
                        "created_at",
                        "session_version",
                        "first_login_at");
    }

    @Test
    void shouldCreateAccountWithUnconfirmedEmail() {
        customerService.register("ana@example.com", PASSWORD);

        Object confirmedAt = jdbc.queryForObject("select email_confirmed_at from customers", Object.class);
        assertThat(confirmedAt).isNull();
    }

    @Test
    @DisplayName("SEC-002: e-mail repetido não confirmado, mesmo com maiúsculas e espaços, troca a senha sem erro")
    void shouldReplacePasswordOfUnconfirmedRepeatedEmailIgnoringCaseAndSpaces() {
        customerService.register("ana@example.com", PASSWORD);

        assertThatCode(() -> customerService.register("  Ana@Example.COM ", "outra senha longa 2"))
                .doesNotThrowAnyException();

        assertThat(countCustomers()).isEqualTo(1);
        String hash = jdbc.queryForObject("select password_hash from customers", String.class);
        assertThat(passwordEncoder.matches("outra senha longa 2", hash)).isTrue();
    }

    @Test
    void shouldKeepOneRowWhenSameEmailRegistersConcurrently() throws Exception {
        registerConcurrently("ana@example.com", 8);

        assertThat(countCustomers()).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-033: a corrida de cadastro não escreve o e-mail no log")
    void shouldNotLogEmailWhenSameEmailRegistersConcurrently(CapturedOutput output) throws Exception {
        registerConcurrently("leakcheck@example.com", 8);

        assertThat(output.getAll()).doesNotContain("leakcheck");
    }

    private void registerConcurrently(String email, int threads) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    customerService.register(email, PASSWORD);
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS); // rethrows if any register threw
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
