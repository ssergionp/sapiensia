package br.com.sergio.sapiensia.product;

import br.com.sergio.sapiensia.admin.AdministratorPrincipal;
import br.com.sergio.sapiensia.customer.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

/**
 * The cover of a Product in the panel (#21): upload or replace, remove, and the preview of the draft. The lock
 * (PRODUCTS_EDIT, both factors) is in SecurityConfig and runs before the body is read. Not transactional on purpose:
 * the service does network calls and owns its transactions. The sent file name and content type are never read: the
 * validator decides the format from the bytes. Logs hold only the exception class.
 */
@RestController
@RequestMapping("/api/admin/products")
class ProductCoverController {

    private static final Logger log = LoggerFactory.getLogger(ProductCoverController.class);

    private final ProductCoverService service;
    private final ClientIpResolver clientIp;

    ProductCoverController(ProductCoverService service, ClientIpResolver clientIp) {
        this.service = service;
        this.clientIp = clientIp;
    }

    /**
     * 200 with the Product; 400 {"errors":{"cover":CODE}}; 413 COVER_TOO_LARGE; 404; 503 without a body. The part is
     * copied to a temporary file that is always deleted, whatever the outcome.
     */
    @PostMapping(path = "/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ProductResponse upload(
            @PathVariable UUID id,
            @RequestPart(name = "file", required = false) MultipartFile file,
            Authentication authentication,
            HttpServletRequest request) {
        if (file == null) {
            throw new ProductCoverRejectedException(ProductCoverRejection.COVER_REQUIRED);
        }
        if (file.isEmpty()) {
            throw new ProductCoverRejectedException(ProductCoverRejection.COVER_EMPTY);
        }
        if (file.getSize() > CoverImageValidator.MAX_BYTES) {
            throw new ProductCoverRejectedException(ProductCoverRejection.COVER_TOO_LARGE);
        }
        var administrator = (AdministratorPrincipal) authentication.getPrincipal();
        Path temp = createTempFile();
        try {
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new ProductFileStorageException("the upload cannot be copied");
            }
            return service.upload(id, temp, administrator.id(), clientIp.resolve(request));
        } finally {
            deleteQuietly(temp);
        }
    }

    /** 204 (also when there was no cover); 404 for a missing Product. */
    @DeleteMapping("/{id}/cover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID id, Authentication authentication, HttpServletRequest request) {
        var administrator = (AdministratorPrincipal) authentication.getPrincipal();
        service.remove(id, administrator.id(), clientIp.resolve(request));
    }

    /** The preview: the same answer as the public route, whatever the status of the Product. */
    @GetMapping("/{id}/cover")
    ResponseEntity<byte[]> preview(
            @PathVariable UUID id, @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String match) {
        StoredCover cover = service.anyCover(id).orElseThrow(ProductNotFoundException::new);
        return CoverResponses.serve(service, cover, match, id);
    }

    private static Path createTempFile() {
        try {
            return Files.createTempFile("product-cover-", ".tmp");
        } catch (IOException e) {
            throw new ProductFileStorageException("no temporary file");
        }
    }

    private static void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn(
                    "A temporary cover file could not be deleted ({})",
                    e.getClass().getName());
        }
    }

    /** The service or this controller refused the file: 400 with the code, or 413 for a size refusal. */
    @ExceptionHandler(ProductCoverRejectedException.class)
    ResponseEntity<ProblemDetail> rejected(ProductCoverRejectedException e) {
        HttpStatus status = e.reason() == ProductCoverRejection.COVER_TOO_LARGE
                ? HttpStatus.PAYLOAD_TOO_LARGE
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(problem(status, e.reason()));
    }

    /** Tomcat stopped the body at the receive limit. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException e) {
        return rejected(new ProductCoverRejectedException(ProductCoverRejection.COVER_TOO_LARGE));
    }

    /** Tomcat could not read the multipart body: there is no usable file. Nothing of the exception is logged. */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ProblemDetail> unreadable(MultipartException e) {
        return rejected(new ProductCoverRejectedException(ProductCoverRejection.COVER_REQUIRED));
    }

    /** Busy validator, Storage or database down: 503 without a body; only the class is logged. */
    @ExceptionHandler({
        ValidatorBusyException.class,
        ProductFileStorageException.class,
        DataAccessException.class,
        TransactionException.class
    })
    ResponseEntity<Void> unavailable(Exception e) {
        log.error("Product cover unavailable ({})", e.getClass().getName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    private static ProblemDetail problem(HttpStatus status, ProductCoverRejection reason) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle("Invalid request");
        problem.setProperty("errors", Map.of("cover", reason.name()));
        return problem;
    }
}
