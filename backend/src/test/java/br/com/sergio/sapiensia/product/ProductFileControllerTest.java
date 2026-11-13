package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import br.com.sergio.sapiensia.admin.AdministratorPrincipal;
import br.com.sergio.sapiensia.admin.Capability;
import br.com.sergio.sapiensia.customer.ClientIpResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.apache.tomcat.util.http.fileupload.InvalidFileNameException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * SEC-047: the temporary copy of the upload is deleted on every way out of the controller, and each failure maps to its
 * answer. A plain unit test: the service is a Mockito mock (the ban on mocks is only for integration tests).
 */
@ExtendWith(OutputCaptureExtension.class)
class ProductFileControllerTest {

    private final ProductFileService service = mock(ProductFileService.class);
    private final ClientIpResolver clientIp = mock(ClientIpResolver.class);
    private final ProductFileController controller = new ProductFileController(service, clientIp);
    private final UUID productId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final List<Path> seen = new ArrayList<>();
    private Authentication authentication;
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        when(clientIp.resolve(any())).thenReturn("203.0.113.9");
        var principal = new AdministratorPrincipal(
                adminId, "painel@example.com", 0, EnumSet.allOf(Capability.class), Instant.now(), Instant.now());
        authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "guia.pdf", "application/pdf", "%PDF-1.4 content".getBytes());
    }

    /** Makes the service record the temp file it receives, check that it holds the bytes, then run {@code outcome}. */
    private void stubService(Supplier<ProductResponse> outcome) {
        when(service.upload(eq(productId), any(), any(), any(), eq(adminId), eq("203.0.113.9"), eq(false)))
                .thenAnswer(invocation -> {
                    Path temp = invocation.getArgument(1);
                    seen.add(temp);
                    assertThat(Files.readString(temp, StandardCharsets.ISO_8859_1))
                            .isEqualTo("%PDF-1.4 content");
                    assertThat(temp.getFileName().toString()).startsWith("product-file-");
                    return outcome.get();
                });
    }

    private void assertTempDeleted() {
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).doesNotExist();
    }

    @Test
    @DisplayName("SEC-047: o temporário é apagado depois de um envio aceito")
    void shouldDeleteTheTempFileAfterSuccess() {
        ProductResponse response = mock(ProductResponse.class);
        stubService(() -> response);

        assertThat(controller.upload(productId, pdf(), authentication, request)).isSameAs(response);

        assertTempDeleted();
    }

    @Test
    @DisplayName("SEC-047: o temporário é apagado numa recusa, inclusive a por tempo esgotado na validação")
    void shouldDeleteTheTempFileAfterARejection() {
        stubService(() -> {
            throw new ProductFileRejectedException(ProductFileRejection.FILE_INVALID);
        });

        assertThatThrownBy(() -> controller.upload(productId, pdf(), authentication, request))
                .isInstanceOf(ProductFileRejectedException.class);

        assertTempDeleted();
    }

    @Test
    @DisplayName("SEC-047: o temporário é apagado quando o validador está ocupado")
    void shouldDeleteTheTempFileWhenTheValidatorIsBusy() {
        stubService(() -> {
            throw new ValidatorBusyException();
        });

        assertThatThrownBy(() -> controller.upload(productId, pdf(), authentication, request))
                .isInstanceOf(ValidatorBusyException.class);

        assertTempDeleted();
    }

    @Test
    @DisplayName("SEC-047: o temporário é apagado quando o Storage falha")
    void shouldDeleteTheTempFileWhenTheStorageFails() {
        stubService(() -> {
            throw new ProductFileStorageException("storage is down");
        });

        assertThatThrownBy(() -> controller.upload(productId, pdf(), authentication, request))
                .isInstanceOf(ProductFileStorageException.class);

        assertTempDeleted();
    }

    @Test
    @DisplayName("SEC-047: o temporário é apagado quando o banco falha")
    void shouldDeleteTheTempFileWhenTheDatabaseFails() {
        stubService(() -> {
            throw new QueryTimeoutException("db is down");
        });

        assertThatThrownBy(() -> controller.upload(productId, pdf(), authentication, request))
                .isInstanceOf(QueryTimeoutException.class);

        assertTempDeleted();
    }

    @Test
    @DisplayName("SEC-047: o temporário é apagado quando o Produto não existe")
    void shouldDeleteTheTempFileWhenTheProductIsMissing() {
        stubService(() -> {
            throw new ProductNotFoundException();
        });

        assertThatThrownBy(() -> controller.upload(productId, pdf(), authentication, request))
                .isInstanceOf(ProductNotFoundException.class);

        assertTempDeleted();
    }

    @Test
    @DisplayName("SEC-047: o temporário é apagado numa exceção inesperada")
    void shouldDeleteTheTempFileOnAnUnexpectedException() {
        stubService(() -> {
            throw new IllegalStateException("boom");
        });

        assertThatThrownBy(() -> controller.upload(productId, pdf(), authentication, request))
                .isInstanceOf(IllegalStateException.class);

        assertTempDeleted();
    }

    @Test
    @DisplayName("SEC-043: parte ausente é FILE_REQUIRED e parte de 0 byte é FILE_EMPTY, sem chamar o serviço")
    void shouldRefuseAMissingAndAnEmptyPartBeforeTheService() {
        assertThatThrownBy(() -> controller.upload(productId, null, authentication, request))
                .isInstanceOfSatisfying(
                        ProductFileRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProductFileRejection.FILE_REQUIRED));
        var empty = new MockMultipartFile("file", "guia.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> controller.upload(productId, empty, authentication, request))
                .isInstanceOfSatisfying(
                        ProductFileRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProductFileRejection.FILE_EMPTY));

        verify(service, never()).upload(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("SEC-043: cada recusa vira 400 com o código, FILE_TOO_LARGE vira 413")
    void shouldMapRejectionsToTheErrorCode() {
        for (ProductFileRejection reason : ProductFileRejection.values()) {
            var answer = controller.rejected(new ProductFileRejectedException(reason));

            HttpStatus expected = switch (reason) {
                case FILE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
                default -> HttpStatus.BAD_REQUEST;
            };
            assertThat(answer.getStatusCode()).isEqualTo(expected);
            ProblemDetail body = answer.getBody();
            assertThat(body.getProperties()).containsEntry("errors", java.util.Map.of("file", reason.name()));
        }
    }

    @Test
    @DisplayName("SEC-008: o atributo da autorização vai ao serviço como reauthenticated=true")
    void shouldPassTheReauthenticatedAttributeToTheService() {
        ProductResponse response = mock(ProductResponse.class);
        request.setAttribute(ProductFileReplacementAccess.REAUTHENTICATED, Boolean.TRUE);
        when(service.upload(eq(productId), any(), any(), any(), eq(adminId), eq("203.0.113.9"), eq(true)))
                .thenReturn(response);

        assertThat(controller.upload(productId, pdf(), authentication, request)).isSameAs(response);
    }

    @Test
    @DisplayName("SEC-008: a recheque do serviço vira 403 com o corpo REAUTHENTICATION_REQUIRED")
    void shouldMapTheReauthenticationRefusalTo403() {
        request.setRequestURI("/api/admin/products/" + productId + "/file");

        var answer = controller.reauthenticationRequired(request);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ProblemDetail body = answer.getBody();
        assertThat(body.getTitle()).isEqualTo("Reauthentication required");
        assertThat(body.getInstance()).hasToString("/api/admin/products/" + productId + "/file");
        assertThat(body.getProperties())
                .containsEntry("errors", java.util.Map.of("reauthentication", "REAUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("SEC-043: o corte do Tomcat durante o recebimento vira 413 FILE_TOO_LARGE")
    void shouldMapTheReceiveLimitTo413() {
        var answer = controller.tooLarge(new MaxUploadSizeExceededException(52_428_800L));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(answer.getBody().getProperties())
                .containsEntry("errors", java.util.Map.of("file", "FILE_TOO_LARGE"));
    }

    @Test
    @DisplayName(
            "SEC-047: nome com NUL recusado pelo Tomcat vira 400 FILE_NOT_PDF; outro corpo ilegível vira FILE_REQUIRED")
    void shouldMapAnUnreadableBody() {
        var nul = controller.unreadable(new MultipartException(
                "Failed to parse", new InvalidFileNameException("a.pdf\0.exe", "Invalid file name")));
        var broken = controller.unreadable(new MultipartException("Failed to parse"));

        assertThat(nul.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(nul.getBody().getProperties()).containsEntry("errors", java.util.Map.of("file", "FILE_NOT_PDF"));
        assertThat(broken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(broken.getBody().getProperties()).containsEntry("errors", java.util.Map.of("file", "FILE_REQUIRED"));
    }

    @Test
    @DisplayName("SEC-047: validador ocupado, Storage e banco fora viram 503 sem corpo")
    void shouldMapUnavailabilityTo503WithoutBody() throws IOException {
        for (Exception e : List.of(
                new ValidatorBusyException(),
                new ProductFileStorageException("storage is down"),
                new QueryTimeoutException("db is down"))) {
            var answer = controller.unavailable(e);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(answer.getBody()).isNull();
        }
    }

    @Test
    @DisplayName("SEC-047: falha do Storage registra o motivo seguro, sem segredo, URL nem nome do arquivo")
    void shouldLogTheSafeStorageReasonWithoutSecretUrlOrFileName(CapturedOutput output) {
        String secret = "test-secret-not-real";
        String url = "https://project.example.test";
        String fileName = "relatorio-cliente-confidencial.pdf";
        RestClient.Builder builder = spy(RestClient.builder());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        doReturn(builder).when(builder).requestFactory(any());
        var storage = new SupabaseProductFileStorage(builder, url, secret, "product-files", "product-covers");
        server.expect(anything())
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"denied " + secret + " " + fileName + "\"}"));
        stubService(() -> {
            Path temp = seen.get(0);
            storage.put(ProductFileKeys.newKey(productId), temp, 1, MediaType.APPLICATION_PDF);
            return null;
        });
        var upload = new MockMultipartFile("file", fileName, "application/pdf", "%PDF-1.4 content".getBytes());

        var failure = catchThrowable(() -> controller.upload(productId, upload, authentication, request));
        var answer = controller.unavailable((Exception) failure);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(answer.getBody()).isNull();
        assertThat(output.getAll())
                .contains("Product file upload unavailable")
                .contains("HTTP 403")
                .doesNotContain(secret)
                .doesNotContain(url)
                .doesNotContain(fileName);
    }

    @Test
    @DisplayName("SEC-047: banco fora registra só a classe, não a mensagem")
    void shouldLogOnlyTheClassForADatabaseFailure(CapturedOutput output) {
        controller.unavailable(new QueryTimeoutException("select * from product where title = 'Guia secreto'"));

        assertThat(output.getAll())
                .contains(QueryTimeoutException.class.getName())
                .doesNotContain("Guia secreto");
    }
}
