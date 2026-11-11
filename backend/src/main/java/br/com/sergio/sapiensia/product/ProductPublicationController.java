package br.com.sergio.sapiensia.product;

import br.com.sergio.sapiensia.admin.AdministratorPrincipal;
import br.com.sergio.sapiensia.customer.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The publication of a Product as a resource that exists or not: PUT makes it exist, DELETE removes it, and both are
 * idempotent. The lock (PRODUCTS_PUBLISH and both factors) is in SecurityConfig and runs before this code.
 */
@RestController
@RequestMapping("/api/admin/products/{id}/publication")
class ProductPublicationController {

    private static final Logger log = LoggerFactory.getLogger(ProductPublicationController.class);

    private final ProductPublicationService service;
    private final ClientIpResolver clientIp;

    ProductPublicationController(ProductPublicationService service, ClientIpResolver clientIp) {
        this.service = service;
        this.clientIp = clientIp;
    }

    /** 200 with the Product; 404; 409 {"errors":{"file":"FILE_REQUIRED"}} when it has no file; 503 without a body. */
    @PutMapping
    ProductResponse publish(@PathVariable UUID id, Authentication authentication, HttpServletRequest request) {
        var administrator = (AdministratorPrincipal) authentication.getPrincipal();
        return service.publish(id, administrator.id(), clientIp.resolve(request));
    }

    /** 200 with the Product (unchanged when it was not published); 404; 503 without a body. */
    @DeleteMapping
    ProductResponse unpublish(@PathVariable UUID id, Authentication authentication, HttpServletRequest request) {
        var administrator = (AdministratorPrincipal) authentication.getPrincipal();
        return service.unpublish(id, administrator.id(), clientIp.resolve(request));
    }

    /** A database or audit failure: 503 without a body, like the other panel routes. */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Void> unavailable(Exception e) {
        // Only the class is logged, since the message could carry Product data.
        log.error(
                "Product publication unavailable: the database or audit write failed ({})",
                e.getClass().getName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
