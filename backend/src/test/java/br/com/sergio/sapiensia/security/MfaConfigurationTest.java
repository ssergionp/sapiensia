package br.com.sergio.sapiensia.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class MfaConfigurationTest {

    @Configuration
    @EnableConfigurationProperties(MfaProperties.class)
    @Import(SecretCipher.class)
    static class MfaTestConfig {}

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(MfaTestConfig.class);

    private static String key(int bytes) {
        return Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    private static String failureChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append('\n');
        }
        return chain.toString();
    }

    @Test
    @DisplayName("SEC-003: the context starts with a 32-byte key")
    void shouldStartWithA32ByteKey() {
        runner.withPropertyValues("app.mfa.encryption-key=" + key(32)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SecretCipher.class);
        });
    }

    @Test
    @DisplayName("SEC-003: a blank, invalid, 16-byte or 31-byte key stops the context without showing the value")
    void shouldFailWithABadKey() {
        for (String bad : new String[] {"", "not base64 !!", key(16), key(31)}) {
            runner.withPropertyValues("app.mfa.encryption-key=" + bad).run(context -> {
                assertThat(context).hasFailed();
                String chain = failureChain(context.getStartupFailure());
                assertThat(chain).contains("Invalid app.mfa.encryption-key");
                if (!bad.isEmpty()) {
                    assertThat(chain).doesNotContain(bad);
                }
            });
        }
    }

    @Test
    @DisplayName("SEC-003: an absent key stops the context")
    void shouldFailWithoutAKey() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureChain(context.getStartupFailure())).contains("Invalid app.mfa.encryption-key");
        });
    }

    @Test
    @DisplayName("SEC-003: the main application.yml has no default for the key and prod holds no key")
    void shouldKeepTheKeyOutOfTheFiles() throws IOException {
        String main = Files.readString(Path.of("src/main/resources/application.yml"));
        String prod = Files.readString(Path.of("src/main/resources/application-prod.yml"));

        assertThat(main).containsPattern("(?m)^\\s*encryption-key:\\s*\\$\\{APP_MFA_ENCRYPTION_KEY\\}\\s*$");
        assertThat(prod).doesNotContain("encryption-key").doesNotContain("APP_MFA_ENCRYPTION_KEY");
    }

    /**
     * The real application.yml and application-prod.yml, with the prod profile. The test property sits above the
     * environment, so an APP_MFA_ENCRYPTION_KEY set on a developer machine or in the CI cannot change the result.
     */
    private ApplicationContextRunner prodRunner(String variable) {
        return runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "spring.config.location=classpath:/application.yml,classpath:/application-prod.yml",
                        "APP_MFA_ENCRYPTION_KEY=" + variable);
    }

    @Test
    @DisplayName("SEC-003: with the prod profile and no usable APP_MFA_ENCRYPTION_KEY the context does not start")
    void shouldFailInProdWithoutTheVariable() {
        // a literal that is not Base64: the placeholder in application.yml has no default, so it resolves to this
        prodRunner("not-a-key !!").run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureChain(context.getStartupFailure())).contains("Invalid app.mfa.encryption-key");
        });
    }

    @Test
    @DisplayName("SEC-003: control: the same prod files start with a valid APP_MFA_ENCRYPTION_KEY, so they are loaded")
    void shouldStartInProdWithTheVariable() {
        String valid = key(32);

        prodRunner(valid).run(context -> {
            assertThat(context).hasNotFailed();
            // the value reached the bean only through the placeholder in the application.yml
            assertThat(context.getBean(MfaProperties.class).encryptionKey()).isEqualTo(valid);
        });
    }
}
