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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

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
                "select column_name from information_schema.columns where table_name = 'customers'", String.class);

        assertThat(columns)
                .containsExactlyInAnyOrder("id", "email", "password_hash", "email_confirmed_at", "created_at");
    }

    @Test
    void shouldCreateAccountWithUnconfirmedEmail() {
        customerService.register("ana@example.com", PASSWORD);

        Object confirmedAt = jdbc.queryForObject("select email_confirmed_at from customers", Object.class);
        assertThat(confirmedAt).isNull();
    }

    @Test
    @DisplayName("SEC-002: e-mail repetido, mesmo com maiúsculas e espaços, é ignorado sem erro")
    void shouldIgnoreRepeatedEmailIgnoringCaseAndSpaces() {
        customerService.register("ana@example.com", PASSWORD);
        String originalHash = jdbc.queryForObject("select password_hash from customers", String.class);

        assertThatCode(() -> customerService.register("  Ana@Example.COM ", "outra senha longa 2"))
                .doesNotThrowAnyException();

        assertThat(countCustomers()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select password_hash from customers", String.class))
                .isEqualTo(originalHash);
    }

    @Test
    void shouldKeepOneRowWhenSameEmailRegistersConcurrently() throws Exception {
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    customerService.register("ana@example.com", PASSWORD);
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

        assertThat(countCustomers()).isEqualTo(1);
    }
}
