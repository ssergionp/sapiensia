package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class FileProductFileStorageTest {

    @TempDir
    Path dir;

    @Test
    void shouldGenerateKeysThatMatchThePatternAndNeverRepeat() {
        UUID productId = UUID.randomUUID();
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String key = ProductFileKeys.newKey(productId);
            assertThat(ProductFileKeys.isValid(key)).isTrue();
            assertThat(key).startsWith("products/" + productId + "/").endsWith(".pdf");
            keys.add(key);
        }
        assertThat(keys).hasSize(100);
    }

    @Test
    void shouldStoreAndDeleteAFile() throws IOException {
        FileProductFileStorage storage = new FileProductFileStorage(dir.resolve("root"));
        Path source = Files.writeString(dir.resolve("source.pdf"), "%PDF-1.7 content", StandardCharsets.UTF_8);
        String key = ProductFileKeys.newKey(UUID.randomUUID());

        storage.put(key, source, Files.size(source));

        Path stored = dir.resolve("root").resolve(key);
        assertThat(stored).hasContent("%PDF-1.7 content");
        storage.delete(key);
        assertThat(stored).doesNotExist();
    }

    @Test
    void shouldNotFailWhenDeletingAMissingObject() {
        FileProductFileStorage storage = new FileProductFileStorage(dir.resolve("root"));

        assertThatCode(() -> storage.delete(ProductFileKeys.newKey(UUID.randomUUID())))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldNeverOverwriteAnExistingObject() throws IOException {
        FileProductFileStorage storage = new FileProductFileStorage(dir.resolve("root"));
        Path first = Files.writeString(dir.resolve("first.pdf"), "first");
        Path second = Files.writeString(dir.resolve("second.pdf"), "second");
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        storage.put(key, first, 5);

        assertThatThrownBy(() -> storage.put(key, second, 6)).isInstanceOf(ProductFileStorageException.class);
        assertThat(dir.resolve("root").resolve(key)).hasContent("first");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../x.pdf",
                "/etc/x.pdf",
                "products\\..\\x.pdf",
                "products/../../x.pdf",
                "products/00000000-0000-0000-0000-000000000000/../x.pdf",
                "products/00000000-0000-0000-0000-000000000000/00000000-0000-0000-0000-000000000000.pdf\u0000.exe",
                ""
            })
    @DisplayName("SEC-047: FileProductFileStorage recusa chave fora do padrão")
    void shouldRefuseAKeyOutsideThePattern(String key) throws IOException {
        FileProductFileStorage storage = new FileProductFileStorage(dir.resolve("root"));
        Path source = Files.writeString(dir.resolve("source.pdf"), "x");

        assertThatThrownBy(() -> storage.put(key, source, 1)).isInstanceOf(ProductFileStorageException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(ProductFileStorageException.class);
        assertThat(dir.resolve("root")).doesNotExist();
    }

    @Test
    @DisplayName("SEC-047: o perfil prod não sobe com o adaptador de disco")
    void shouldRefuseToStartWithTheProdProfile() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> new FileProductFileStorage(dir.toString(), prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.storage.provider=file")
                .hasMessageContaining("prod");
    }

    @Test
    void shouldStartWithoutTheProdProfile() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThatCode(() -> new FileProductFileStorage(dir.toString(), local)).doesNotThrowAnyException();
    }
}
