package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

// Own context: the limiter state is in memory. Default limits (5 failures lock the account).
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.isolated-context=admin-customer-key-collision")
class AdminCustomerKeyCollisionTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("SEC-002: um login de Cliente com o e-mail \"admin:<e-mail>\" não cai no balde do Administrador")
    void shouldNotLockTheAdministratorThroughTheCustomerLogin() throws Exception {
        jdbc.update("delete from administrators");
        String email = "colisao-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into administrators (email, password_hash) values (?, ?)",
                email,
                passwordEncoder.encode(PASSWORD));
        AdminBrowser browser = new AdminBrowser(port);
        for (int i = 0; i < 5; i++) {
            assertThat(browser.loginCustomer("admin:" + email, "senha-errada-45678")
                            .statusCode())
                    .isEqualTo(401);
        }

        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(204);
    }
}
