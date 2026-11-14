package br.com.sergio.sapiensia.product;

import br.com.sergio.sapiensia.customer.CurrentCustomer;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues the 60-second download link of a Product in the Library (FR-034, SEC-011): only the logged-in Customer with
 * an active Access. No Access, another Customer's Product, a cancelled Access, a missing Product and a Product without
 * a file are the same empty 404.
 */
@RestController
class LibraryDownloadController {

    private static final Logger log = LoggerFactory.getLogger(LibraryDownloadController.class);

    record DownloadLink(String url) {}

    private final ProductDownloadService service;

    LibraryDownloadController(ProductDownloadService service) {
        this.service = service;
    }

    /** 200 with the link; 404 empty; 400 when the id is not a UUID; 503 without a body. */
    @PostMapping("/api/library/{productId}/download")
    ResponseEntity<DownloadLink> issue(@PathVariable UUID productId, Authentication authentication) {
        try {
            return service.issue(CurrentCustomer.id(authentication), productId)
                    .map(url -> ResponseEntity.ok(new DownloadLink(url)))
                    .orElseThrow(ProductNotFoundException::new);
        } catch (ProductFileStorageException | DataAccessException | TransactionException e) {
            // Storage or database down: 503 without a body; only the Product id and the class are logged.
            log.error(
                    "Library download unavailable (product {}, {})",
                    productId,
                    e.getClass().getName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}
