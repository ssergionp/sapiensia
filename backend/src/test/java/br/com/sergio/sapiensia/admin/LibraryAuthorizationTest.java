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

/** SEC-010: the Library is for the Customer only; the Administrator is refused (403), the visitor gets 401. */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=library-authorization", "app.login-limit.ip-max-attempts=1000"})
class LibraryAuthorizationTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow

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
    }

    @Test
    @DisplayName("SEC-010: shouldRefuseAnonymousWith401AndAdministratorWith403")
    void shouldRefuseAnonymousWith401AndAdministratorWith403() throws Exception {
        AdminBrowser anonymous = new AdminBrowser(port);
        AdminBrowser administrator = new AdminBrowser(port);
        assertThat(administrator
                        .loginAdminFully(adminEmail, PASSWORD, secret, clock)
                        .statusCode())
                .isEqualTo(204);

        assertThat(anonymous.get("/api/library").statusCode()).isEqualTo(401);
        assertThat(administrator.get("/api/library").statusCode()).isEqualTo(403);

        String cover = "/api/library/" + UUID.randomUUID() + "/cover";
        assertThat(anonymous.get(cover).statusCode()).isEqualTo(401);
        assertThat(administrator.get(cover).statusCode()).isEqualTo(403);
    }
}
