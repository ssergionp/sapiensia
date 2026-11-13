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

    private static final MediaType PDF = MediaType.APPLICATION_PDF;

    private static final String SECRET = "test-secret-not-real";
    private static final String URL = "https://project.example.test";
    private static final String BUCKET = "product-files";
    private static final String COVER_BUCKET = "product-covers";

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

        assertThatCode(() -> fixture.storage.put(key, file, Files.size(file), PDF))
                .doesNotThrowAnyException();
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

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1, PDF))
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

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1, PDF))
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

        assertThatThrownBy(() -> fixture.storage.put("../x.pdf", file, 1, PDF))
                .isInstanceOf(ProductFileStorageException.class);
        assertThatThrownBy(() -> fixture.storage.delete("products/x.pdf"))
                .isInstanceOf(ProductFileStorageException.class);
        fixture.server.verify();
    }

    @Test
    @DisplayName("#21: put de capa vai ao bucket de capas com o Content-Type recebido")
    void shouldPostACoverToTheCoverBucketWithItsMediaType() throws IOException {
        Fixture fixture = fixture();
        Path file = Files.write(dir.resolve("c.png"), new byte[] {1});
        String key = ProductFileKeys.newCoverKey(UUID.randomUUID(), CoverFormat.PNG);
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + COVER_BUCKET + "/" + key))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "image/png"))
                .andRespond(withSuccess("{\"Key\":\"x\"}", MediaType.APPLICATION_JSON));

        fixture.storage.put(key, file, 1, MediaType.IMAGE_PNG);
        fixture.server.verify();
    }

    @Test
    @DisplayName("#21: delete de capa usa o bucket de capas")
    void shouldDeleteACoverFromTheCoverBucket() {
        Fixture fixture = fixture();
        String key = ProductFileKeys.newCoverKey(UUID.randomUUID(), CoverFormat.WEBP);
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + COVER_BUCKET + "/" + key))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());

        fixture.storage.delete(key);
        fixture.server.verify();
    }

    @Test
    @DisplayName("#21: get lê a capa pela rota autenticada do bucket de capas")
    void shouldReadACoverThroughTheAuthenticatedRoute() {
        Fixture fixture = fixture();
        String key = ProductFileKeys.newCoverKey(UUID.randomUUID(), CoverFormat.JPEG);
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/authenticated/" + COVER_BUCKET + "/" + key))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("apikey", SECRET))
                .andRespond(withSuccess(new byte[] {9, 8, 7}, MediaType.IMAGE_JPEG));

        assertThat(fixture.storage.get(key)).contains(new byte[] {9, 8, 7});
        fixture.server.verify();
    }

    @Test
    @DisplayName("#21: get devolve vazio para 404 e para 400 com statusCode 404")
    void shouldReturnEmptyWhenTheObjectDoesNotExist() {
        Fixture fixture = fixture();
        String key = ProductFileKeys.newCoverKey(UUID.randomUUID(), CoverFormat.PNG);
        String url = URL + "/storage/v1/object/authenticated/" + COVER_BUCKET + "/" + key;
        fixture.server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        fixture.server
                .expect(requestTo(url))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"statusCode\":\"404\",\"error\":\"not_found\",\"message\":\"Object not found\"}"));

        assertThat(fixture.storage.get(key)).isEmpty();
        assertThat(fixture.storage.get(key)).isEmpty();
    }

    @Test
    @DisplayName("#21: get falha com ProductFileStorageException em HTTP 500 e em outros 400")
    void shouldFailTheReadOnServerErrors() {
        Fixture fixture = fixture();
        String key = ProductFileKeys.newCoverKey(UUID.randomUUID(), CoverFormat.PNG);
        String url = URL + "/storage/v1/object/authenticated/" + COVER_BUCKET + "/" + key;
        fixture.server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        fixture.server
                .expect(requestTo(url))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"statusCode\":\"403\",\"error\":\"Unauthorized\",\"message\":\"no\"}"));

        assertThatThrownBy(() -> fixture.storage.get(key))
                .isInstanceOf(ProductFileStorageException.class)
                .hasMessage("Storage could not read the file: HTTP 500");
        assertThatThrownBy(() -> fixture.storage.get(key))
                .isInstanceOf(ProductFileStorageException.class)
                .hasMessage("Storage could not read the file: HTTP 400");
    }

    @Test
    @DisplayName("#21: get recusa corpo maior que o limite da capa e aceita o limite exato")
    void shouldRefuseABodyBiggerThanTheCoverLimit() {
        Fixture fixture = fixture();
        String key = ProductFileKeys.newCoverKey(UUID.randomUUID(), CoverFormat.PNG);
        String url = URL + "/storage/v1/object/authenticated/" + COVER_BUCKET + "/" + key;
        fixture.server
                .expect(requestTo(url))
                .andRespond(withSuccess(new byte[(int) CoverImageValidator.MAX_BYTES + 1], MediaType.IMAGE_PNG));
        fixture.server
                .expect(requestTo(url))
                .andRespond(withSuccess(new byte[(int) CoverImageValidator.MAX_BYTES], MediaType.IMAGE_PNG));

        assertThatThrownBy(() -> fixture.storage.get(key)).isInstanceOf(ProductFileStorageException.class);
        assertThat(fixture.storage.get(key))
                .hasValueSatisfying(bytes -> assertThat(bytes).hasSize((int) CoverImageValidator.MAX_BYTES));
    }

    @Test
    @DisplayName("SEC-047: o log do erro de capa nunca mostra segredo, URL, caminho dos buckets nem chave")
    void shouldNeverLogTheSecretTheUrlTheCoverPathOrTheKey(CapturedOutput output) {
        Fixture fixture = fixture();
        String key = ProductFileKeys.newCoverKey(UUID.randomUUID(), CoverFormat.PNG);
        // fake key built at run time, so the whole value never sits in the source (secret scanners)
        String otherKey = String.join("", "sb_", "secret_", "otherKey", "0123456789");
        String body = "{\"statusCode\":\"403\",\"error\":\"" + otherKey + "\",\"message\":\"" + URL
                + "/storage/v1/object/authenticated/" + COVER_BUCKET + "/" + key + " and /storage/v1/object/"
                + COVER_BUCKET + "/ and " + key + " with " + SECRET + "\"}";
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/authenticated/" + COVER_BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));

        assertThatThrownBy(() -> fixture.storage.get(key)).isInstanceOf(ProductFileStorageException.class);
        assertThat(output.getAll())
                .contains("Storage could not read the file: HTTP 400 (statusCode=")
                .doesNotContain(SECRET)
                .doesNotContain("sb_secret_")
                .doesNotContain("project.example.test")
                .doesNotContain(key)
                .doesNotContain(COVER_BUCKET)
                .doesNotContain("/storage/v1/object/");
    }

    @Test
    void shouldRefuseToStartWithoutTheSecretKey() {
        assertThatThrownBy(() -> new SupabaseProductFileStorage(RestClient.builder(), URL, " ", BUCKET, COVER_BUCKET))
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

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1, PDF))
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
        // fake key built at run time, so the whole value never sits in the source (secret scanners)
        String otherKey = String.join("", "sb_", "secret_", "otherKey", "0123456789");
        String host = "project.example.test";
        String body = "{\"statusCode\":\"" + SECRET + "\",\"error\":\"" + otherKey + " at " + host + "\",\"message\":\""
                + URL + "/storage/v1/object/" + BUCKET + "/" + key + " not found\"}";
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1, PDF))
                .isInstanceOf(ProductFileStorageException.class);
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
        // fake tokens ({"role":"fake"}, {"fake":true}) built at run time, so no whole token sits in the source
        String jwt = String.join(".", "eyJ" + "hbGciOiJIUzI1NiJ9", "eyJ" + "yb2xlIjoiZmFrZSJ9", "ZmFrZS1zaWduYXR1cmU");
        String twoParts = String.join(".", "eyJ" + "hbGciOiJub25lIn0", "eyJ" + "mYWtlIjp0cnVlfQ");
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"statusCode\":\"403\",\"error\":\"" + twoParts + "\",\"message\":\"bad token " + jwt
                                + "\"}"));

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1, PDF))
                .isInstanceOf(ProductFileStorageException.class);
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

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1, PDF))
                .isInstanceOf(ProductFileStorageException.class);
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

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1, PDF))
                .isInstanceOf(ProductFileStorageException.class);
        assertThat(output.getAll()).contains("message=" + "m".repeat(200) + ")").doesNotContain("m".repeat(201));
    }

    @Test
    @DisplayName("SEC-047: o log do erro do Storage nunca mostra um nome de arquivo, em nenhum campo")
    void shouldNeverLogAFileNameInAnyField(CapturedOutput output) throws IOException {
        String log = logOfAStorageError(
                output,
                "{\"statusCode\":\"x.pdf\",\"error\":\"a.b.docx\",\"message\":\"denied relatorio-cliente.pdf\"}");

        assertThat(log)
                .contains("(statusCode=<redacted>, error=<redacted>, message=denied <redacted>)")
                .doesNotContain("x.pdf")
                .doesNotContain("a.b.docx")
                .doesNotContain("relatorio-cliente.pdf");
    }

    @Test
    @DisplayName("SEC-047: o log do erro do Storage mantém legíveis as mensagens comuns do Supabase")
    void shouldKeepTheCommonStorageMessagesReadable(CapturedOutput output) throws IOException {
        String log = logOfAStorageError(
                output,
                "{\"statusCode\":\"404\",\"error\":\"Bucket not found\","
                        + "\"message\":\"mime type application/pdf is not supported\"}");

        assertThat(log)
                .contains(
                        "(statusCode=404, error=Bucket not found, message=mime type application/pdf is not supported)");
    }

    @Test
    @DisplayName("SEC-047: o log do erro do Storage esconde vários nomes de arquivo na mesma frase")
    void shouldHideSeveralFileNamesInTheSameSentence(CapturedOutput output) throws IOException {
        String log = logOfAStorageError(
                output, "{\"statusCode\":\"400\",\"error\":\"e\",\"message\":\"cannot copy a.pdf to b.txt or c.zip\"}");

        assertThat(log).contains("message=cannot copy <redacted> to <redacted> or <redacted>)");
    }

    @Test
    @DisplayName("SEC-047: o log do erro do Storage esconde a extensão em maiúsculas")
    void shouldHideAnUppercaseExtension(CapturedOutput output) throws IOException {
        String log = logOfAStorageError(
                output, "{\"statusCode\":\"400\",\"error\":\"e\",\"message\":\"denied CONTRATO.PDF\"}");

        assertThat(log).contains("message=denied <redacted>)").doesNotContain("CONTRATO");
    }

    private String logOfAStorageError(CapturedOutput output, String body) throws IOException {
        Fixture fixture = fixture();
        Path file = Files.writeString(dir.resolve("f.pdf"), "x");
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));

        assertThatThrownBy(() -> fixture.storage.put(key, file, 1, PDF))
                .isInstanceOf(ProductFileStorageException.class);
        return output.getAll();
    }

    @Test
    void shouldNotLogASuccessfulUpload(CapturedOutput output) throws IOException {
        Fixture fixture = fixture();
        Path file = Files.writeString(dir.resolve("f.pdf"), "x");
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        fixture.server
                .expect(requestTo(URL + "/storage/v1/object/" + BUCKET + "/" + key))
                .andRespond(withSuccess("{\"Key\":\"x\"}", MediaType.APPLICATION_JSON));

        fixture.storage.put(key, file, 1, PDF);

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
        return new Fixture(server, new SupabaseProductFileStorage(builder, URL, SECRET, BUCKET, COVER_BUCKET));
    }

    private record Fixture(MockRestServiceServer server, SupabaseProductFileStorage storage) {}
}
