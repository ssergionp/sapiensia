package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * SEC-010: the download route is for the Customer only, with CSRF, and POST only. No case here may write a Download
 * record. Isolated context and its own Customer, never deleted, because download_records is insert-only (a record
 * pins its Customer), so a cleanup could hit the guard in a sibling test.
 */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=library-download-authorization", "app.login-limit.ip-max-attempts=1000"})
class LibraryDownloadAuthorizationTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow
    private static final String CUSTOMER_PASSWORD = "senhalongaok"; // gitleaks:allow

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AdminMfaTestSupport.MutableClock clock;

    private byte[] secret;
    private String adminEmail;
    private String customerEmail;
    private UUID customerId;
    private final UUID productId = UUID.randomUUID();
    private String path;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from administrators");
        adminEmail = "painel-" + UUID.randomUUID() + "@example.com";
        UUID adminId = UUID.randomUUID();
        jdbc.update(
                "insert into administrators (id, email, password_hash) values (?, ?, ?)",
                adminId,
                adminEmail,
                passwordEncoder.encode(PASSWORD));
        secret = AdminMfaTestSupport.enableMfa(jdbc, cipher, adminId);
        customerEmail = "cliente-" + UUID.randomUUID() + "@example.com";
        customerId = jdbc.queryForObject(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, ?, now()) returning id",
                UUID.class,
                customerEmail,
                passwordEncoder.encode(CUSTOMER_PASSWORD));
        path = "/api/library/" + productId + "/download";
    }

    private long records() {
        return jdbc.queryForObject(
                "select count(*) from download_records where customer_id = ?", Long.class, customerId);
    }

    @Test
    @DisplayName("SEC-010: anônimo recebe 401 e o Administrador com os dois fatores recebe 403")
    void shouldRefuseAnonymousWith401AndAdministratorWith403() throws Exception {
        AdminBrowser anonymous = new AdminBrowser(port);
        AdminBrowser administrator = new AdminBrowser(port);
        assertThat(administrator
                        .loginAdminFully(adminEmail, PASSWORD, secret, clock)
                        .statusCode())
                .isEqualTo(204);

        assertThat(anonymous.post(path, "").statusCode()).isEqualTo(401);
        assertThat(administrator.post(path, "").statusCode()).isEqualTo(403);
        assertThat(records()).isZero();
    }

    @Test
    @DisplayName("SEC-010: Cliente sem token CSRF recebe 403")
    void shouldRefuseACustomerWithoutTheCsrfToken() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginCustomer(customerEmail, CUSTOMER_PASSWORD).statusCode())
                .isEqualTo(204);

        assertThat(browser.postWithoutCsrf(path, "").statusCode()).isEqualTo(403);
        assertThat(records()).isZero();
    }

    @Test
    @DisplayName("SEC-010: GET, PUT e DELETE na rota de download são negados")
    void shouldDenyEveryVerbButPost() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        assertThat(browser.loginCustomer(customerEmail, CUSTOMER_PASSWORD).statusCode())
                .isEqualTo(204);

        assertThat(browser.get(path).statusCode()).isEqualTo(403);
        assertThat(browser.put(path, "").statusCode()).isEqualTo(403);
        assertThat(browser.delete(path).statusCode()).isEqualTo(403);
        assertThat(records()).isZero();
    }
}
