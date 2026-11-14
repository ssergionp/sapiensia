package br.com.sergio.sapiensia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ProdProfileConfigTest {

    private static final String[] DB_KEYS = {
        "spring.datasource.url", "spring.datasource.username", "spring.datasource.password"
    };

    private static PropertySource<?> load() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("prod", new ClassPathResource("application-prod.yml"));
        return sources.get(0);
    }

    private static String prod(String key) throws IOException {
        return String.valueOf(load().getProperty(key));
    }

    private static StandardEnvironment environment(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("vars", variables));
        environment.getPropertySources().addLast(load());
        return environment;
    }

    @Test
    @DisplayName("SEC-014: prod profile reads the client IP from Fly-Client-IP")
    void prodProfileUsesFlyClientIp() throws IOException {
        assertThat(prod("app.login-limit.client-ip-header")).isEqualTo("Fly-Client-IP");
    }

    @Test
    @DisplayName("ADR-0003: prod profile uses the Asaas gateway, with no default address and a blank key by default")
    void prodProfileUsesAsaasWithoutDefaults() throws IOException {
        assertThat(prod("app.pix.provider")).isEqualTo("asaas");
        assertThat(prod("app.asaas.api.base-url")).isEqualTo("${APP_ASAAS_API_BASE_URL}");
        assertThat(prod("app.asaas.api.key")).isEqualTo("${APP_ASAAS_API_KEY:}");

        StandardEnvironment missing = environment(Map.of());
        assertThatThrownBy(() -> missing.getRequiredProperty("app.asaas.api.base-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not resolve placeholder");
        assertThat(missing.getRequiredProperty("app.asaas.api.key")).isEmpty();
    }

    @Test
    @DisplayName("SEC-022: prod profile has no default for the database settings")
    void prodProfileHasNoDatabaseDefaults() throws IOException {
        assertThat(prod("spring.datasource.url")).isEqualTo("${DB_URL}");
        assertThat(prod("spring.datasource.username")).isEqualTo("${DB_USER}");
        assertThat(prod("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
    }

    @Test
    @DisplayName(
            "SEC-022: prod profile fails to resolve the database settings when DB_URL, DB_USER or DB_PASSWORD is missing")
    void prodProfileFailsWithoutDatabaseVariables() throws IOException {
        StandardEnvironment missing = environment(Map.of());
        for (String key : DB_KEYS) {
            assertThatThrownBy(() -> missing.getRequiredProperty(key))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Could not resolve placeholder");
        }

        StandardEnvironment present =
                environment(Map.of("DB_URL", "jdbc:postgresql://db/x", "DB_USER", "u", "DB_PASSWORD", "p"));
        assertThat(present.getRequiredProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db/x");
        assertThat(present.getRequiredProperty("spring.datasource.username")).isEqualTo("u");
        assertThat(present.getRequiredProperty("spring.datasource.password")).isEqualTo("p");
    }
}
