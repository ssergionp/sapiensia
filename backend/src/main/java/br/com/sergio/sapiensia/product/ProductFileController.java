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
import org.apache.tomcat.util.http.fileupload.InvalidFileNameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

/**
 * The PDF of a Product in the panel. The lock (PRODUCTS_FILE, both factors and, for a Product that was ever published, a recent
 * authentication: ProductFileReplacementAccess) is in SecurityConfig and runs before the body is read. This class is not transactional on purpose: the service does network calls and owns its transactions.
 * The submitted file name goes only to the validator, which checks the extension; it is never logged or stored.
 */
@RestController
@RequestMapping("/api/admin/products")
class ProductFileController {

    private static final Logger log = LoggerFactory.getLogger(ProductFileController.class);

    private final ProductFileService service;
    private final ClientIpResolver clientIp;

    ProductFileController(ProductFileService service, ClientIpResolver clientIp) {
        this.service = service;
        this.clientIp = clientIp;
    }

    /**
     * 200 with the Product; 400 {"errors":{"file":CODE}}; 413 FILE_TOO_LARGE; 403 REAUTHENTICATION_REQUIRED (the
     * service re-check under the lock); 404; 503 without a body. The part is copied to a temporary file that is
     * always deleted, whatever the outcome.
     */
    @PostMapping(path = "/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ProductResponse upload(
            @PathVariable UUID id,
            @RequestPart(name = "file", required = false) MultipartFile file,
            Authentication authentication,
            HttpServletRequest request) {
        if (file == null) {
            throw new ProductFileRejectedException(ProductFileRejection.FILE_REQUIRED);
        }
        if (file.isEmpty()) {
            throw new ProductFileRejectedException(ProductFileRejection.FILE_EMPTY);
        }
        var administrator = (AdministratorPrincipal) authentication.getPrincipal();
        Path temp = createTempFile();
        try {
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new ProductFileStorageException("the upload cannot be copied");
            }
            return service.upload(
                    id,
                    temp,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    administrator.id(),
                    clientIp.resolve(request),
                    Boolean.TRUE.equals(request.getAttribute(ProductFileReplacementAccess.REAUTHENTICATED)));
        } finally {
            deleteQuietly(temp);
        }
    }

    private static Path createTempFile() {
        try {
            return Files.createTempFile("product-file-", ".tmp");
        } catch (IOException e) {
            throw new ProductFileStorageException("no temporary file");
        }
    }

    private static void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn(
                    "A temporary upload file could not be deleted ({})",
                    e.getClass().getName());
        }
    }

    /** The service or this controller refused the file: 400 with the code, or 413 for a size refusal. */
    @ExceptionHandler(ProductFileRejectedException.class)
    ResponseEntity<ProblemDetail> rejected(ProductFileRejectedException e) {
        HttpStatus status = switch (e.reason()) {
            case FILE_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(problem(status, e.reason()));
    }

    /** The re-check under the row lock found the Product published since the request was let in as a draft. */
    @ExceptionHandler(ProductFileReauthenticationRequiredException.class)
    ResponseEntity<ProblemDetail> reauthenticationRequired(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProductFileReplacementAccess.problem(request));
    }

    /** Tomcat stopped the body at the limit (file part over 50 MB or request over 51 MB). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(problem(HttpStatus.PAYLOAD_TOO_LARGE, ProductFileRejection.FILE_TOO_LARGE));
    }

    /**
     * Tomcat could not read the multipart body. A file name with a control character (a NUL, say) is refused by Tomcat
     * itself before any code of ours runs: that is the same refusal as a bad extension. Any other broken body means
     * there is no usable file. Nothing of the exception is logged, since its message can quote the submitted name.
     */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ProblemDetail> unreadable(MultipartException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidFileNameException) {
                return rejected(new ProductFileRejectedException(ProductFileRejection.FILE_NOT_PDF));
            }
        }
        return rejected(new ProductFileRejectedException(ProductFileRejection.FILE_REQUIRED));
    }

    /**
     * Busy validator, storage or database down: 503 without a body. Only the class is logged, plus the message of a
     * storage failure: those messages are fixed texts with at most an HTTP status or a class name, while the others
     * can carry data.
     */
    @ExceptionHandler({
        ValidatorBusyException.class,
        ProductFileStorageException.class,
        DataAccessException.class,
        TransactionException.class
    })
    ResponseEntity<Void> unavailable(Exception e) {
        if (e instanceof ProductFileStorageException) {
            log.error("Product file upload unavailable ({}: {})", e.getClass().getName(), e.getMessage());
        } else {
            log.error("Product file upload unavailable ({})", e.getClass().getName());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    private static ProblemDetail problem(HttpStatus status, ProductFileRejection reason) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle("Invalid request");
        problem.setProperty("errors", Map.of("file", reason.name()));
        return problem;
    }
}
