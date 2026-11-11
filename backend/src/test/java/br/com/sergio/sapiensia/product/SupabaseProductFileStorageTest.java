package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class SupabaseProductFileStorageTest {

    private static final String SECRET = "test-secret-not-real";
    private static final String URL = "https://project.example.test";
    private static final String BUCKET = "product-files";

    @TempDir
    Path dir;

    @Test
    @DisplayName("SEC-047: SupabaseProductFileStorage manda apikey, x-upsert false e content-type")
    void shouldPostTheFileWithApikeyNoUpsertAndPdfContentType() throws IOException {
        Fixture fixture = fixture();
        Path file = Files.writeString(dir.resolve("f.pdf"), "%PDF-1.7 content");
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", SECRET))
                .andExpect(header("x-upsert", "false"))
                .andExpect(header("Content-Type", "application/pdf"))
                .andExpect(request -> {
                    if (request.getHeaders().containsHeader("Authorization")) {
                        throw new AssertionError("the secret key must not go in Authorization");
                    }
                })
                .andRespond(withSuccess("{\"Key\":\"x\"}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> fixture.storage.put(key, file, Files.size(file))).doesNotThrowAnyException();
        fixture.server.verify();
    }

    @Test
    void shouldDeleteTheObject() {
        Fixture fixture = fixture();
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("apikey", SECRET))
                .andRespond(withSuccess());

        assertThatCode(() -> fixture.storage.delete(key)).doesNotThrowAnyException();
        fixture.server.verify();
    }

    @Test
    void shouldTreatAMissingObjectAsDeleted() {
        Fixture fixture = fixture();
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatCode(() -> fixture.storage.delete(key)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SEC-047: erro do Storage vira exceção só com o status, sem segredo, corpo nem URL")
    void shouldFailWithStatusOnly() throws IOException {
        Fixture fixture = fixture();
        Path file = Files.writeString(dir.resolve("f.pdf"), "x");
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"boom " + SECRET + "\"}"));

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1))
                .isInstanceOf(ProductFileStorageException.class)
                .hasMessageContaining("HTTP 500")
                .hasNoCause()
                .message()
                .doesNotContain(SECRET)
                .doesNotContain("boom")
                .doesNotContain(URL)
                .doesNotContain(key);
    }

    @Test
    void shouldFailDeleteWithStatusOnly() {
        Fixture fixture = fixture();
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> fixture.storage.delete(key))
                .isInstanceOf(ProductFileStorageException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("SEC-047: falha de conexão não vaza segredo nem URL")
    void shouldWrapConnectionFailureWithoutSecret() throws IOException {
        Fixture fixture = fixture();
        Path file = Files.writeString(dir.resolve("f.pdf"), "x");
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(request -> {
                    throw new IOException("reset talking to " + URL + " with " + SECRET);
                });

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1))
                .isInstanceOf(ProductFileStorageException.class)
                .hasNoCause()
                .message()
                .doesNotContain(SECRET)
                .doesNotContain(URL);
    }

    @Test
    void shouldRefuseAKeyOutsideThePatternWithoutCallingTheStorage() throws IOException {
        Fixture fixture = fixture();
        Path file = Files.writeString(dir.resolve("f.pdf"), "x");

        assertThatThrownBy(() -> fixture.storage.put("../x.pdf", file, 1))
                .isInstanceOf(ProductFileStorageException.class);
        assertThatThrownBy(() -> fixture.storage.delete("products/x.pdf"))
                .isInstanceOf(ProductFileStorageException.class);
        fixture.server.verify();
    }

    @Test
    void shouldRefuseToStartWithoutTheSecretKey() {
        assertThatThrownBy(() -> new SupabaseProductFileStorage(RestClient.builder(), URL, " ", BUCKET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_STORAGE_SUPABASE_SECRET_KEY is required");
    }

    @Test
    @DisplayName("SEC-047: erro do Storage no envio registra statusCode, error e message do corpo")
    void shouldLogStatusCodeErrorAndMessageOfAStorageError(CapturedOutput output) throws IOException {
        Fixture fixture = fixture();
        Path file = Files.writeString(dir.resolve("f.pdf"), "x");
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"statusCode\":\"403\",\"error\":\"Unauthorized\",\"message\":\"invalid signature\"}"));

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1))
                .isInstanceOf(ProductFileStorageException.class)
                .hasMessage("Storage could not store the file: HTTP 400");
        assertThat(output.getAll())
                .contains("Storage could not store the file: HTTP 400 "
                        + "(statusCode=403, error=Unauthorized, message=invalid signature)");
    }

    @Test
    @DisplayName("SEC-047: o log do erro do Storage nunca mostra a chave, a URL do projeto nem o caminho do objeto")
    void shouldNeverLogTheSecretTheUrlOrTheObjectPath(CapturedOutput output) throws IOException {
        Fixture fixture = fixture();
        Path file = Files.writeString(dir.resolve("f.pdf"), "x");
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        String otherKey = "sb_secret_otherKey0123456789";
        String host = "project.example.test";
        String body = "{\"statusCode\":\"" + SECRET + "\",\"error\":\"" + otherKey + " at " + host + "\",\"message\":\""
                + URL + "/storage/v1/object/" + BUCKET + "/" + key + " not found\"}";
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1)).isInstanceOf(ProductFileStorageException.class);
        assertThat(output.getAll())
                .contains("Storage could not store the file: HTTP 400 (statusCode=")
                .contains("not found")
                .doesNotContain(SECRET)
                .doesNotContain("sb_secret_")
                .doesNotContain(host)
                .doesNotContain(key)
                .doesNotContain("/storage/v1/object/");
    }

    @Test
    @DisplayName("SEC-047: o log do erro do Storage nunca mostra um token JWT do Supabase")
    void shouldNeverLogALegacyJwtKey(CapturedOutput output) throws IOException {
        Fixture fixture = fixture();
        Path file = Files.writeString(dir.resolve("f.pdf"), "x");
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiZmFrZSJ9.ZmFrZS1zaWduYXR1cmU";
        String twoParts = "eyJhbGciOiJub25lIn0.eyJmYWtlIjp0cnVlfQ";
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"statusCode\":\"403\",\"error\":\"" + twoParts + "\",\"message\":\"bad token " + jwt
                                + "\"}"));

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1)).isInstanceOf(ProductFileStorageException.class);
        assertThat(output.getAll()).contains("message=bad token <redacted>").doesNotContain("eyJ");
    }

    @Test
    void shouldLogOnlyTheStatusWhenTheBodyIsNotJson(CapturedOutput output) throws IOException {
        Fixture fixture = fixture();
        Path file = Files.writeString(dir.resolve("f.pdf"), "x");
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>boom</html>"));

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1)).isInstanceOf(ProductFileStorageException.class);
        assertThat(output.getAll())
                .contains("Storage could not store the file: HTTP 502")
                .doesNotContain("statusCode=")
                .doesNotContain("boom");
    }

    @Test
    void shouldCutTheFieldsAt200Characters(CapturedOutput output) throws IOException {
        Fixture fixture = fixture();
        Path file = Files.writeString(dir.resolve("f.pdf"), "x");
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"statusCode\":\"400\",\"error\":\"e\",\"message\":\"" + "m".repeat(500) + "\"}"));

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1)).isInstanceOf(ProductFileStorageException.class);
        assertThat(output.getAll()).contains("message=" + "m".repeat(200) + ")").doesNotContain("m".repeat(201));
    }

    @Test
    void shouldNotLogASuccessfulUpload(CapturedOutput output) throws IOException {
        Fixture fixture = fixture();
        Path file = Files.writeString(dir.resolve("f.pdf"), "x");
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withSuccess("{\"Key\":\"x\"}", MediaType.APPLICATION_JSON));

        fixture.storage.put(key, file, 1);

        assertThat(output.getAll()).doesNotContain("Storage could not");
    }

    @Test
    @DisplayName("SEC-047: erro do Storage ao apagar registra os mesmos campos, sem segredo")
    void shouldLogTheSafeReasonOfADeleteError(CapturedOutput output) {
        Fixture fixture = fixture();
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"statusCode\":\"403\",\"error\":\"Unauthorized\",\"message\":\"" + SECRET + "\"}"));

        assertThatThrownBy(() -> fixture.storage.delete(key))
                .isInstanceOf(ProductFileStorageException.class)
                .hasMessage("Storage could not delete the file: HTTP 400");
        assertThat(output.getAll())
                .contains("Storage could not delete the file: HTTP 400 (statusCode=403, error=Unauthorized")
                .doesNotContain(SECRET);
    }

    @Test
    void shouldNotLogAMissingObjectOnDelete(CapturedOutput output) {
        Fixture fixture = fixture();
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"statusCode\":\"404\",\"error\":\"not_found\",\"message\":\"Object not found\"}"));

        assertThatCode(() -> fixture.storage.delete(key)).doesNotThrowAnyException();
        assertThat(output.getAll()).doesNotContain("Storage could not");
    }

    private Fixture fixture() {
        RestClient.Builder builder = spy(RestClient.builder());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // keep the mock request factory: the storage would otherwise replace it with the real one
        doReturn(builder).when(builder).requestFactory(any());
        return new Fixture(server, new SupabaseProductFileStorage(builder, URL, SECRET, BUCKET));
    }

    private record Fixture(MockRestServiceServer server, SupabaseProductFileStorage storage) {}
}
