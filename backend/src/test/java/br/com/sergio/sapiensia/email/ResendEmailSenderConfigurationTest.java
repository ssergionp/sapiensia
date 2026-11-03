package br.com.sergio.sapiensia.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Starts the Resend sender through Spring, because the unit test builds it by hand and hid that the context has no
 * RestClient.Builder bean.
 */
class ResendEmailSenderConfigurationTest {

    private static final String SECRET = "re_test_key_value";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ResendEmailSender.class)
            .withPropertyValues("app.email.provider=resend", "app.email.from=noreply@example.com");

    @Test
    void shouldStartWithTheResendProviderAndAnApiKey() {
        runner.withPropertyValues("app.email.resend.api-key=" + SECRET).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EmailSender.class);
        });
    }

    @Test
    void shouldFailFastNamingTheVariableWhenTheApiKeyIsBlank() {
        runner.withPropertyValues("app.email.resend.api-key=").run(context -> {
            assertThat(context).hasFailed();
            StringBuilder chain = new StringBuilder();
            for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
                chain.append(t.getMessage()).append('\n');
            }
            assertThat(chain.toString()).contains("RESEND_API_KEY").doesNotContain(SECRET);
        });
    }
}
