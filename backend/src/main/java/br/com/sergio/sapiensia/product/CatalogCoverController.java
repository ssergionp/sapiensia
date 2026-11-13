package br.com.sergio.sapiensia.product;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public cover of a published Product (#21, ADR-0007): GET only, open to any Visitor (see SecurityConfig). A
 * draft, an unpublished, a missing or cover-less Product is the same empty 404 of ProductNotFoundException, and the
 * "published" check comes before the ETag one, so an unpublished Product never answers 304.
 */
@RestController
@RequestMapping("/api/products")
class CatalogCoverController {

    private static final Logger log = LoggerFactory.getLogger(CatalogCoverController.class);

    private final ProductCoverService service;

    CatalogCoverController(ProductCoverService service) {
        this.service = service;
    }

    /** 200 with the image; 304 for the same ETag; 404 empty; 400 when the id is not a UUID; 503 without a body. */
    @GetMapping("/{id}/cover")
    ResponseEntity<byte[]> get(
            @PathVariable UUID id, @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String match) {
        StoredCover cover = service.publishedCover(id).orElseThrow(ProductNotFoundException::new);
        return CoverResponses.serve(service, cover, match, id);
    }

    /** Storage or database down: 503 without a body; only the class is logged. */
    @ExceptionHandler({ProductFileStorageException.class, DataAccessException.class, TransactionException.class})
    ResponseEntity<Void> unavailable(Exception e) {
        log.error("Product cover read unavailable ({})", e.getClass().getName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
