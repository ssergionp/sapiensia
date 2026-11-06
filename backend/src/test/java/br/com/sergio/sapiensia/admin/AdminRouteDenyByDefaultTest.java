package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A route under /api/admin that SecurityConfig knows nothing about must stay denied, even for an Administrator who
 * holds PANEL_ACCESS: each new panel feature adds its own capability and rule.
 */
@Import({TestcontainersConfiguration.class, AdminRouteDenyByDefaultTest.ProbeConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-route-deny", "app.login-limit.ip-max-attempts=1000"})
class AdminRouteDenyByDefaultTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/admin/probe")
        String probe() {
            return "reached";
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private String adminEmail;
    private String customerEmail;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from administrators");
        adminEmail = "painel-" + UUID.randomUUID() + "@example.com";
        customerEmail = "cliente-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into administrators (email, password_hash) values (?, ?)",
                adminEmail,
                passwordEncoder.encode(PASSWORD));
        jdbc.update(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, ?, now())",
                customerEmail,
                passwordEncoder.encode(PASSWORD));
    }

    @Test
    @DisplayName("SEC-010: rota de /api/admin sem regra dá 401 ao anônimo e 403 ao Cliente e ao Administrador")
    void shouldDenyUnknownAdminRoutesByDefault() throws Exception {
        AdminBrowser anonymous = new AdminBrowser(port);
        AdminBrowser customer = new AdminBrowser(port);
        AdminBrowser administrator = new AdminBrowser(port);
        assertThat(customer.loginCustomer(customerEmail, PASSWORD).statusCode()).isEqualTo(204);
        assertThat(administrator.loginAdmin(adminEmail, PASSWORD).statusCode()).isEqualTo(204);
        assertThat(administrator.get("/api/admin/sessions/current").statusCode())
                .isEqualTo(200);

        assertThat(anonymous.get("/api/admin/probe").statusCode())
                .as("anonymous probe")
                .isEqualTo(401);
        assertThat(customer.get("/api/admin/probe").statusCode())
                .as("customer probe")
                .isEqualTo(403);
        assertThat(administrator.get("/api/admin/probe").statusCode())
                .as("administrator probe")
                .isEqualTo(403);
        for (String path : new String[] {"/api/admin", "/api/administrators"}) {
            assertThat(anonymous.post(path, "{}").statusCode())
                    .as("anonymous " + path)
                    .isEqualTo(401);
            assertThat(customer.post(path, "{}").statusCode())
                    .as("customer " + path)
                    .isEqualTo(403);
            assertThat(administrator.post(path, "{}").statusCode())
                    .as("administrator " + path)
                    .isEqualTo(403);
        }
    }
}
