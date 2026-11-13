package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.sergio.sapiensia.admin.AdministratorPrincipal;
import br.com.sergio.sapiensia.admin.Capability;
import br.com.sergio.sapiensia.customer.ClientIpResolver;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

/**
 * SEC-047 SEC-017: the temporary copy of a cover upload is deleted on every way out of the controller, the sent file
 * name and type are never read, and each failure maps to its answer. A plain unit test with a mocked service.
 */
@ExtendWith(OutputCaptureExtension.class)
class ProductCoverControllerTest {

    private final ProductCoverService service = mock(ProductCoverService.class);
    private final ClientIpResolver clientIp = mock(ClientIpResolver.class);
    private final ProductCoverController controller = new ProductCoverController(service, clientIp);
    private final UUID productId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final List<Path> seen = new ArrayList<>();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        when(clientIp.resolve(any())).thenReturn("203.0.113.9");
        var principal = new AdministratorPrincipal(
                adminId, "painel@example.com", 0, EnumSet.allOf(Capability.class), Instant.now(), Instant.now());
        authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("file", "capa.png", "image/png", "image-bytes".getBytes());
    }

    private void stubService(Supplier<ProductResponse> outcome) {
        when(service.upload(eq(productId), any(), eq(adminId), eq("203.0.113.9")))
                .thenAnswer(invocation -> {
                    Path temp = invocation.getArgument(1);
                    seen.add(temp);
                    assertThat(Files.readString(temp)).isEqualTo("image-bytes");
                    assertThat(temp.getFileName().toString()).startsWith("product-cover-");
                    return outcome.get();
                });
    }

    private void assertTempDeleted() {
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).doesNotExist();
    }

    @Test
    @DisplayName("SEC-047: the temporary copy is deleted after an accepted upload")
    void shouldDeleteTheTempFileAfterSuccess() {
        ProductResponse response = mock(ProductResponse.class);
        stubService(() -> response);

        assertThat(controller.upload(productId, image(), authentication, request))
                .isSameAs(response);

        assertTempDeleted();
    }

    @Test
    @DisplayName("SEC-047: the temporary copy is deleted after a rejection, a missing Product, an outage or a bug")
    void shouldDeleteTheTempFileOnEveryFailure() {
        List<RuntimeException> failures = List.of(
                new ProductCoverRejectedException(ProductCoverRejection.COVER_INVALID),
                new ProductNotFoundException(),
                new ProductFileStorageException("storage is down"),
                new QueryTimeoutException("db is down"),
                new ValidatorBusyException(),
                new IllegalStateException("boom"));

        for (RuntimeException failure : failures) {
            seen.clear();
            stubService(() -> {
                throw failure;
            });

            assertThatThrownBy(() -> controller.upload(productId, image(), authentication, request))
                    .isSameAs(failure);

            assertTempDeleted();
        }
    }

    @Test
    @DisplayName("SEC-017: the sent file name and content type are never read")
    void shouldNeverReadTheSentNameOrType() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getInputStream()).thenAnswer(i -> new ByteArrayInputStream("image-bytes".getBytes()));
        stubService(() -> mock(ProductResponse.class));

        controller.upload(productId, file, authentication, request);

        verify(file, never()).getOriginalFilename();
        verify(file, never()).getContentType();
        verify(file, never()).getName();
    }

    @Test
    @DisplayName("FR-016: a missing part is COVER_REQUIRED and an empty one COVER_EMPTY, without calling the service")
    void shouldRefuseAMissingAndAnEmptyPartBeforeTheService() {
        assertThatThrownBy(() -> controller.upload(productId, null, authentication, request))
                .isInstanceOfSatisfying(
                        ProductCoverRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProductCoverRejection.COVER_REQUIRED));
        var empty = new MockMultipartFile("file", "capa.png", "image/png", new byte[0]);
        assertThatThrownBy(() -> controller.upload(productId, empty, authentication, request))
                .isInstanceOfSatisfying(
                        ProductCoverRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(ProductCoverRejection.COVER_EMPTY));

        verify(service, never()).upload(any(), any(), any(), any());
    }

    @Test
    @DisplayName("FR-016: each rejection is a 400 with the code, COVER_TOO_LARGE is 413")
    void shouldMapRejectionsToTheErrorCode() {
        for (ProductCoverRejection reason : ProductCoverRejection.values()) {
            var answer = controller.rejected(new ProductCoverRejectedException(reason));

            HttpStatus expected = reason == ProductCoverRejection.COVER_TOO_LARGE
                    ? HttpStatus.PAYLOAD_TOO_LARGE
                    : HttpStatus.BAD_REQUEST;
            assertThat(answer.getStatusCode()).isEqualTo(expected);
            assertThat(answer.getBody().getTitle()).isEqualTo("Invalid request");
            assertThat(answer.getBody().getProperties()).containsEntry("errors", Map.of("cover", reason.name()));
        }
    }

    @Test
    @DisplayName("FR-016: the Tomcat receive limit is a 413 COVER_TOO_LARGE; an unreadable body is COVER_REQUIRED")
    void shouldMapTheReceiveLimitAndAnUnreadableBody() {
        var tooLarge = controller.tooLarge(new MaxUploadSizeExceededException(52_428_800L));
        var broken = controller.unreadable(new MultipartException("Failed to parse"));

        assertThat(tooLarge.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(tooLarge.getBody().getProperties()).containsEntry("errors", Map.of("cover", "COVER_TOO_LARGE"));
        assertThat(broken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(broken.getBody().getProperties()).containsEntry("errors", Map.of("cover", "COVER_REQUIRED"));
    }

    @Test
    @DisplayName("SEC-047: busy validator, Storage and database outages are 503 without body; the log has only classes")
    void shouldMapUnavailabilityTo503AndLogOnlyClasses(CapturedOutput output) {
        for (Exception e : List.of(
                new ValidatorBusyException(),
                new ProductFileStorageException("storage is down https://project.example.test"),
                new QueryTimeoutException("select * from product where title = 'Guia secreto'"))) {
            var answer = controller.unavailable(e);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(answer.getBody()).isNull();
        }
        assertThat(output.getAll())
                .contains(QueryTimeoutException.class.getName())
                .doesNotContain("Guia secreto")
                .doesNotContain("project.example.test");
    }
}
