package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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

    @Test
    @DisplayName("SEC-007: a tabela guarda só o hash e o token tem 32 bytes")
    void shouldStoreOnlyTheHashOfA32ByteToken() {
        UUID id = newCustomer();

        KnownDevices.DeviceCheck check = devices.recognize(id, null);

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

        KnownDevices.DeviceCheck check = devices.recognize(id, "abc");

        assertThat(check.token()).isNotEqualTo("abc").hasSize(43);
    }
}
