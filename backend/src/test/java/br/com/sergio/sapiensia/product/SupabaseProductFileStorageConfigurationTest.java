package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Starts the storage adapters through Spring, as the unit tests build them by hand. */
class SupabaseProductFileStorageConfigurationTest {

    private static final String SECRET = "test-secret-not-real";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SupabaseProductFileStorage.class, FileProductFileStorage.class)
            .withPropertyValues(
                    "app.storage.supabase.url=https://project.example.test",
                    "app.storage.supabase.bucket=product-files");

    @Test
    void shouldStartWithTheSupabaseProviderAndASecretKey() {
        runner.withPropertyValues("app.storage.provider=supabase", "app.storage.supabase.secret-key=" + SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProductFileStorage.class);
                    assertThat(context).hasSingleBean(SupabaseProductFileStorage.class);
                });
    }

    @Test
    void shouldFailFastNamingTheVariableWhenTheSecretKeyIsBlank() {
        runner.withPropertyValues("app.storage.provider=supabase", "app.storage.supabase.secret-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(chain(context.getStartupFailure()))
                            .contains("APP_STORAGE_SUPABASE_SECRET_KEY is required")
                            .doesNotContain(SECRET);
                });
    }

    @Test
    void shouldStartWithTheFileProviderOutsideProd() {
        runner.withPropertyValues("app.storage.provider=file", "app.storage.file.dir=target/test-storage-config")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FileProductFileStorage.class);
                });
    }

    @Test
    @DisplayName("SEC-047: o perfil prod falha ao iniciar com app.storage.provider=file")
    void shouldFailToStartWithTheFileProviderAndTheProdProfile() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "app.storage.provider=file",
                        "app.storage.file.dir=target/test-storage-config")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(chain(context.getStartupFailure())).contains("app.storage.provider=file");
                });
    }

    private static String chain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append('\n');
        }
        return chain.toString();
    }
}
