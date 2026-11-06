package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

// Own context: the panel login switched off, as in the prod profile until MFA (#15).
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-panel-login-disabled", "app.admin.panel-login-enabled=false"})
class AdminPanelLoginDisabledTest {

    private static final String PASSWORD = "senha-do-painel-123"; // gitleaks:allow

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("SEC-001: com a trava desligada, o login do painel responde 404 mesmo com a senha certa")
    void shouldAnswer404WhenThePanelLoginIsDisabled() throws Exception {
        jdbc.update("delete from administrators");
        String email = "trava-" + UUID.randomUUID() + "@example.com";
        jdbc.update(
                "insert into administrators (email, password_hash) values (?, ?)",
                email,
                passwordEncoder.encode(PASSWORD));
        AdminBrowser browser = new AdminBrowser(port);

        assertThat(browser.loginAdmin(email, PASSWORD).statusCode()).isEqualTo(404);
        assertThat(browser.get("/api/admin/sessions/current").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-001: a trava vem desligada no padrão e no perfil prod, e ligada só no perfil local")
    void shouldShipThePanelLoginOffOutsideTheLocalProfile() {
        assertThat(panelLoginEnabledIn("application.yml")).isEqualTo("false");
        assertThat(panelLoginEnabledIn("application-prod.yml")).isEqualTo("false");
        assertThat(panelLoginEnabledIn("application-local.yml")).isEqualTo("true");
    }

    private static String panelLoginEnabledIn(String resource) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(resource));
        Properties properties = yaml.getObject();
        return properties == null ? null : properties.getProperty("app.admin.panel-login-enabled");
    }
}
