package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
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
class PasswordResetTokensTest {

    @Autowired
    private PasswordResetTokens tokens;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID customerId;

    @BeforeEach
    void createCustomer() {
        customerId = UUID.randomUUID();
        jdbc.update(
                "insert into customers (id, email, password_hash) values (?, ?, 'x')",
                customerId,
                "reset-tokens-" + customerId + "@example.com");
    }

    private long rows() {
        return jdbc.queryForObject(
                "select count(*) from password_reset_tokens where customer_id = ?", Long.class, customerId);
    }

    @Test
    @DisplayName("SEC-007: o token tem 32 bytes aleatórios e o banco guarda só o hash")
    void shouldIssueRandomTokensAndStoreOnlyTheirHash() {
        String first = tokens.issue(customerId);
        String second = tokens.issue(customerId);

        assertThat(first).isNotEqualTo(second).matches("[A-Za-z0-9_-]{43}");
        assertThat(second).matches("[A-Za-z0-9_-]{43}");
        List<String> hashes = jdbc.queryForList(
                "select token_hash from password_reset_tokens where customer_id = ?", String.class, customerId);
        assertThat(hashes).hasSize(2).allSatisfy(hash -> assertThat(hash).matches("[0-9a-f]{64}"));
        String wholeTable = jdbc.queryForObject(
                "select coalesce(string_agg(t::text, ' '), '') from password_reset_tokens t", String.class);
        assertThat(wholeTable).doesNotContain(first).doesNotContain(second);
    }

    @Test
    @DisplayName("SEC-007: o link vale 30 minutos")
    void shouldExpireTheLinkAfterThirtyMinutes() {
        tokens.issue(customerId);

        Long seconds = jdbc.queryForObject(
                "select extract(epoch from expires_at - created_at)::bigint from password_reset_tokens"
                        + " where customer_id = ?",
                Long.class,
                customerId);
        assertThat(seconds).isBetween(1799L, 1801L);
    }

    @Test
    @DisplayName("SEC-007: revokeUnused apaga os links não usados do cliente e preserva os usados e os de outros")
    void shouldRevokeOnlyUnusedLinksOfTheCustomer() {
        UUID other = UUID.randomUUID();
        jdbc.update(
                "insert into customers (id, email, password_hash) values (?, ?, 'x')",
                other,
                "reset-other-" + other + "@example.com");
        tokens.issue(customerId);
        tokens.issue(customerId);
        tokens.issue(other);
        tokens.issue(customerId);
        jdbc.update(
                "update password_reset_tokens set used_at = now() where id = (select id from password_reset_tokens"
                        + " where customer_id = ? limit 1)",
                customerId);

        tokens.revokeUnused(customerId);

        assertThat(rows()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from password_reset_tokens where customer_id = ? and used_at is not null",
                        Long.class,
                        customerId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from password_reset_tokens where customer_id = ?", Long.class, other))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-007: pedidos paralelos da mesma conta deixam só um link válido")
    void shouldLeaveOnlyOneUnusedLinkWhenReissuedInParallel() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return tokens.reissue(customerId);
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).matches("[A-Za-z0-9_-]{43}");
            }
        }

        assertThat(rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-033: a tabela de links só guarda o necessário")
    void shouldStoreOnlyWhatIsNeeded() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'password_reset_tokens'",
                String.class);

        assertThat(columns)
                .containsExactlyInAnyOrder("id", "customer_id", "token_hash", "expires_at", "used_at", "created_at");
    }

    @Test
    void shouldDeleteTokensWhenCustomerIsDeleted() {
        tokens.issue(customerId);

        jdbc.update("delete from customers where id = ?", customerId);

        assertThat(rows()).isZero();
    }
}
