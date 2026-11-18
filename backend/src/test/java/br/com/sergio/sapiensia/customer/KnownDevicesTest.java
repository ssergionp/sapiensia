package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.Tokens;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class KnownDevicesTest {

    @Autowired
    KnownDevices devices;

    @Autowired
    JdbcTemplate jdbc;

    private UUID newCustomer() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into customers (id, email, password_hash, email_confirmed_at) values (?, ?, 'x', now())",
                id,
                "known-" + id + "@example.com");
        return id;
    }

    private int deviceRows(UUID customerId) {
        return jdbc.queryForObject(
                "select count(*) from customer_devices where customer_id = ?", Integer.class, customerId);
    }

    private Timestamp firstLoginAt(UUID customerId) {
        return jdbc.queryForObject("select first_login_at from customers where id = ?", Timestamp.class, customerId);
    }

    @Test
    @DisplayName("SEC-007: a tabela guarda só o hash e o token tem 32 bytes")
    void shouldStoreOnlyTheHashOfA32ByteToken() {
        UUID id = newCustomer();

        KnownDevices.DeviceCheck check = devices.recognize(id, 0, null);

        assertThat(check.notifyOwner()).isFalse(); // the first login of the account
        assertThat(Base64.getUrlDecoder().decode(check.token())).hasSize(32);
        List<Map<String, Object>> rows = jdbc.queryForList("select * from customer_devices where customer_id = ?", id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("token_hash")).isEqualTo(EmailConfirmationTokens.hash(check.token()));
        assertThat(rows.get(0).values().stream().map(String::valueOf)).noneMatch(v -> v.contains(check.token()));
    }

    @Test
    @DisplayName("SEC-007: um token malformado não é gravado")
    void shouldNotStoreAMalformedToken() {
        UUID id = newCustomer();

        KnownDevices.DeviceCheck check = devices.recognize(id, 0, "abc");

        assertThat(check.token()).isNotEqualTo("abc").hasSize(43);
    }

    @Test
    @DisplayName("SEC-007: um token bem formado e desconhecido é trocado por um token do servidor e avisa o dono")
    void shouldReplaceAnUnknownWellFormedTokenWithAServerToken() {
        UUID id = newCustomer();
        devices.recognize(id, 0, null);
        String planted = "A".repeat(43);

        KnownDevices.DeviceCheck check = devices.recognize(id, 0, planted);

        assertThat(check.token()).isNotEqualTo(planted);
        assertThat(check.notifyOwner()).isTrue();
        Integer rows = jdbc.queryForObject(
                "select count(*) from customer_devices where token_hash = ?", Integer.class, Tokens.hash(planted));
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("SEC-007: um login que leu a versão de sessão anterior à troca de senha não registra o aparelho")
    void shouldNotRegisterWhenTheSessionVersionChanged() {
        UUID id = newCustomer();
        jdbc.update("update customers set session_version = 1 where id = ?", id);

        KnownDevices.DeviceCheck check = devices.recognize(id, 0, null);

        assertThat(check).isEqualTo(KnownDevices.DeviceCheck.NOT_REGISTERED);
        assertThat(deviceRows(id)).isZero();
        assertThat(firstLoginAt(id)).isNull();
    }

    @Test
    @DisplayName("SEC-007: com a versão de sessão trocada, um aparelho conhecido não é atualizado")
    void shouldNotRefreshAKnownDeviceWhenTheSessionVersionChanged() {
        UUID id = newCustomer();
        String known = devices.recognize(id, 0, null).token();
        jdbc.update("update customer_devices set last_seen_at = '2020-01-01T00:00:00Z' where customer_id = ?", id);
        Timestamp lastSeenBefore = jdbc.queryForObject(
                "select last_seen_at from customer_devices where customer_id = ?", Timestamp.class, id);
        jdbc.update("update customers set session_version = 1 where id = ?", id);

        KnownDevices.DeviceCheck check = devices.recognize(id, 0, known);

        assertThat(check).isEqualTo(KnownDevices.DeviceCheck.NOT_REGISTERED);
        assertThat(jdbc.queryForObject(
                        "select last_seen_at from customer_devices where customer_id = ?", Timestamp.class, id))
                .isEqualTo(lastSeenBefore);
    }

    @Test
    @DisplayName("SEC-007: uma conta que não existe mais não registra aparelho e não lança exceção")
    void shouldNotRegisterForAnAccountThatNoLongerExists() {
        UUID id = UUID.randomUUID();

        KnownDevices.DeviceCheck check = devices.recognize(id, 0, null);

        assertThat(check).isEqualTo(KnownDevices.DeviceCheck.NOT_REGISTERED);
        assertThat(deviceRows(id)).isZero();
    }

    @Test
    @DisplayName("SEC-007: se a marca do primeiro login falha, nenhum aparelho fica gravado")
    void shouldLeaveNoDeviceWhenTheFirstLoginUpdateFails() {
        UUID id = newCustomer();
        jdbc.execute("create function fail_first_login() returns trigger language plpgsql as"
                + " $$ begin raise exception 'first login update failed'; end $$");
        try {
            jdbc.execute("create trigger fail_first_login before update of first_login_at on customers"
                    + " for each row when (new.id = '" + id + "'::uuid) execute function fail_first_login()");

            assertThatThrownBy(() -> devices.recognize(id, 0, null)).isInstanceOf(DataAccessException.class);

            assertThat(deviceRows(id)).isZero();
        } finally {
            jdbc.execute("drop trigger if exists fail_first_login on customers");
            jdbc.execute("drop function if exists fail_first_login()");
        }
    }

    @Test
    @DisplayName("SEC-007: de dois primeiros logins simultâneos, exatamente um conta como o primeiro")
    void shouldCountExactlyOneOfTwoSimultaneousFirstLogins() throws Exception {
        UUID id = newCustomer();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<KnownDevices.DeviceCheck>> futures = List.of(
                    pool.submit(() -> {
                        go.await();
                        return devices.recognize(id, 0, null);
                    }),
                    pool.submit(() -> {
                        go.await();
                        return devices.recognize(id, 0, null);
                    }));
            go.countDown();
            KnownDevices.DeviceCheck first = futures.get(0).get(10, TimeUnit.SECONDS);
            KnownDevices.DeviceCheck second = futures.get(1).get(10, TimeUnit.SECONDS);

            assertThat(deviceRows(id)).isEqualTo(2);
            assertThat(List.of(first.notifyOwner(), second.notifyOwner())).containsExactlyInAnyOrder(false, true);
        } finally {
            pool.shutdownNow();
        }
    }
}
