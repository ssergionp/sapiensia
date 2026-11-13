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
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;

class FileProductFileStorageTest {

    private static final MediaType PDF = MediaType.APPLICATION_PDF;

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

        storage.put(key, source, Files.size(source), PDF);

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
        storage.put(key, first, 5, PDF);

        assertThatThrownBy(() -> storage.put(key, second, 6, PDF)).isInstanceOf(ProductFileStorageException.class);
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

        assertThatThrownBy(() -> storage.put(key, source, 1, PDF)).isInstanceOf(ProductFileStorageException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(ProductFileStorageException.class);
        assertThat(dir.resolve("root")).doesNotExist();
    }

    @Test
    @DisplayName("#21: o disco guarda, lê e apaga uma capa e devolve vazio para chave inexistente")
    void shouldStoreReadAndDeleteACover() throws IOException {
        FileProductFileStorage storage = new FileProductFileStorage(dir.resolve("root"));
        Path source = Files.write(dir.resolve("c.png"), new byte[] {1, 2, 3});
        String key = ProductFileKeys.newCoverKey(UUID.randomUUID(), CoverFormat.PNG);
        assertThat(key).matches("^covers/[0-9a-f-]{36}/[0-9a-f-]{36}\\.png$");
        assertThat(ProductFileKeys.isValid(key)).isTrue();
        assertThat(storage.get(key)).isEmpty();

        storage.put(key, source, 3, org.springframework.http.MediaType.IMAGE_PNG);

        assertThat(storage.get(key)).contains(new byte[] {1, 2, 3});
        storage.delete(key);
        assertThat(storage.get(key)).isEmpty();
    }

    @Test
    @DisplayName("#21: o disco recusa ler objeto maior que o limite da capa")
    void shouldRefuseToReadAnObjectBiggerThanTheCoverLimit() throws IOException {
        FileProductFileStorage storage = new FileProductFileStorage(dir.resolve("root"));
        Path source = Files.write(dir.resolve("big.png"), new byte[(int) CoverImageValidator.MAX_BYTES + 1]);
        String key = ProductFileKeys.newCoverKey(UUID.randomUUID(), CoverFormat.PNG);
        storage.put(key, source, Files.size(source), org.springframework.http.MediaType.IMAGE_PNG);

        assertThatThrownBy(() -> storage.get(key)).isInstanceOf(ProductFileStorageException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "covers/../x.png",
                "covers/00000000-0000-0000-0000-000000000000/../x.png",
                "covers/00000000-0000-0000-0000-000000000000/00000000-0000-0000-0000-000000000000.gif",
                "covers/00000000-0000-0000-0000-000000000000/00000000-0000-0000-0000-000000000000.pdf"
            })
    @DisplayName("#21: o disco recusa chave de capa fora do padrão")
    void shouldRefuseACoverKeyOutsideThePattern(String key) throws IOException {
        FileProductFileStorage storage = new FileProductFileStorage(dir.resolve("root"));
        Path source = Files.writeString(dir.resolve("s.png"), "x");

        assertThatThrownBy(() -> storage.put(key, source, 1, org.springframework.http.MediaType.IMAGE_PNG))
                .isInstanceOf(ProductFileStorageException.class);
        assertThatThrownBy(() -> storage.get(key)).isInstanceOf(ProductFileStorageException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(ProductFileStorageException.class);
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
