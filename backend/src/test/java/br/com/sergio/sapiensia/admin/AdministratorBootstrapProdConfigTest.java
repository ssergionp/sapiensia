package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AdministratorBootstrapProdConfigTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(Validator.class, () -> VALIDATOR)
            .withUserConfiguration(ConfiguredAdministrator.class);

    private static String failureChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append('\n');
        }
        return chain.toString();
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: no prod, e-mail vazio impede a subida e cita a variável")
    void shouldFailInProdWhenTheEmailIsBlank() {
        runner.withPropertyValues("app.admin.required=true", "app.admin.email=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(failureChain(context.getStartupFailure())).contains("APP_ADMIN_EMAIL");
        });
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: no prod, e-mail inválido impede a subida sem mostrar o valor")
    void shouldFailInProdWhenTheEmailIsInvalid() {
        runner.withPropertyValues("app.admin.required=true", "app.admin.email=nao-e-email")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureChain(context.getStartupFailure()))
                            .contains("APP_ADMIN_EMAIL")
                            .doesNotContain("nao-e-email");
                });
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: no prod, e-mail válido sobe e é normalizado")
    void shouldStartInProdWithAValidEmail() {
        runner.withPropertyValues("app.admin.required=true", "app.admin.email=Boss@Example.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ConfiguredAdministrator.class).email())
                            .contains("boss@example.com");
                });
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: fora do prod, e-mail vazio sobe e fica sem Administrador configurado")
    void shouldStartOutsideProdWithABlankEmail() {
        runner.withPropertyValues("app.admin.required=false", "app.admin.email=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ConfiguredAdministrator.class).email())
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("SEC-007 / ADR-0004: fora do prod, e-mail vazio ou inválido pula o bootstrap com aviso e sem linha")
    void shouldSkipTheBootstrapOutsideProd(CapturedOutput output) {
        for (String value : new String[] {"", "nao-e-email"}) {
            ConfiguredAdministrator configured = new ConfiguredAdministrator(value, false, VALIDATOR);
            Administrators administrators = mock(Administrators.class);
            SchedulerClient scheduler = mock(SchedulerClient.class);
            AdministratorBootstrap bootstrap =
                    new AdministratorBootstrap(administrators, scheduler, configured, Duration.ofHours(1));

            assertThat(bootstrap.bootstrap()).isEqualTo(BootstrapResult.SKIPPED_NOT_CONFIGURED);

            verifyNoInteractions(administrators, scheduler);
        }
        assertThat(output).contains("administrator bootstrap: skipped").doesNotContain("nao-e-email");
    }
}
