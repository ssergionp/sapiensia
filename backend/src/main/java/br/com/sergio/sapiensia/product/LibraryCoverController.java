package br.com.sergio.sapiensia.product;

import br.com.sergio.sapiensia.customer.CurrentCustomer;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The cover of a Product in the Library (#32): only for the logged-in Customer with an active Access to it, published
 * or not. No Access, no cover, another Customer's Product or a cancelled Access is the same empty 404, and the Access
 * check comes before the ETag one, so a cancelled Access never answers 304. The public route stays published-only.
 */
@RestController
class LibraryCoverController {

    private static final Logger log = LoggerFactory.getLogger(LibraryCoverController.class);

    private final ProductCoverService service;

    LibraryCoverController(ProductCoverService service) {
        this.service = service;
    }

    /** 200 with the image; 304 for the same ETag; 404 empty; 400 when the id is not a UUID; 503 without a body. */
    @GetMapping("/api/library/{productId}/cover")
    ResponseEntity<byte[]> get(
            @PathVariable UUID productId,
            Authentication authentication,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String match) {
        StoredCover cover = service.ownedCover(CurrentCustomer.id(authentication), productId)
                .orElseThrow(ProductNotFoundException::new);
        return CoverResponses.serve(service, cover, match, productId);
    }

    /** Storage or database down: 503 without a body; only the class is logged. */
    @ExceptionHandler({ProductFileStorageException.class, DataAccessException.class, TransactionException.class})
    ResponseEntity<Void> unavailable(Exception e) {
        log.error("Library cover read unavailable ({})", e.getClass().getName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
