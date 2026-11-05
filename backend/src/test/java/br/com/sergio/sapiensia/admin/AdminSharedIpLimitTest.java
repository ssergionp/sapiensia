package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

// Own context: the limiter state is in memory. The IP limit is the default (20 attempts per window).
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.isolated-context=admin-shared-ip")
class AdminSharedIpLimitTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName("SEC-014: o limite por IP é um só para o login do Cliente e o do painel")
    void shouldShareTheIpBudgetBetweenCustomerAndPanelLogins() throws Exception {
        AdminBrowser browser = new AdminBrowser(port);
        for (int i = 0; i < 20; i++) {
            assertThat(browser.loginCustomer("ninguem-" + UUID.randomUUID() + "@example.com", "senha-qualquer-123")
                            .statusCode())
                    .isEqualTo(401);
        }

        assertThat(browser.loginAdmin("painel@example.com", "senha-qualquer-123")
                        .statusCode())
                .isEqualTo(429);
    }
}
