package br.com.sergio.sapiensia.product;

import br.com.sergio.sapiensia.admin.AdministratorPrincipal;
import br.com.sergio.sapiensia.customer.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Products in the panel. Every rule is in SecurityConfig (panel(PRODUCTS_EDIT) for create, list, read and text edit;
 * panel(PRODUCTS_PRICE) for the price change): the capability and both factors are checked before this code runs, so
 * the answer to a denied request never depends on the Product.
 */
@RestController
@RequestMapping("/api/admin/products")
class ProductAdminController {

    private static final Logger log = LoggerFactory.getLogger(ProductAdminController.class);

    private final ProductAdminService service;
    private final ClientIpResolver clientIp;

    ProductAdminController(ProductAdminService service, ClientIpResolver clientIp) {
        this.service = service;
        this.clientIp = clientIp;
    }

    /** 201 with the Location and the Product in DRAFT; 400 with the error code of each field; 503 without a body. */
    @PostMapping
    ResponseEntity<ProductResponse> create(
            @Valid @RequestBody ProductRequest body, Authentication authentication, HttpServletRequest request) {
        var administrator = (AdministratorPrincipal) authentication.getPrincipal();
        ProductResponse created = service.create(body, administrator.id(), clientIp.resolve(request));
        return ResponseEntity.created(URI.create("/api/admin/products/" + created.id()))
                .body(created);
    }

    /** 200 with a page of 20; 400 for a negative or non-numeric page; a page past the end is empty. */
    @GetMapping
    ProductPage list(@RequestParam(defaultValue = "0") @Min(0) int page) {
        return service.list(page);
    }

    /** 200; 404 when no Product has the id; 400 when the id is not a UUID. */
    @GetMapping("/{id}")
    ProductResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** 200 with the Product; 400 per field; 404; 409 when the version is stale. The price is not part of this body. */
    @PutMapping("/{id}")
    ProductResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody ProductUpdateRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var administrator = (AdministratorPrincipal) authentication.getPrincipal();
        return service.update(id, body, administrator.id(), clientIp.resolve(request));
    }

    /** Same answers as the text edit, for the price only (SEC-029). */
    @PutMapping("/{id}/price")
    ProductResponse changePrice(
            @PathVariable UUID id,
            @Valid @RequestBody ProductPriceRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var administrator = (AdministratorPrincipal) authentication.getPrincipal();
        return service.changePrice(id, body, administrator.id(), clientIp.resolve(request));
    }

    /** A database or audit failure: 503 without a body, like the other panel routes. */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Void> unavailable(Exception e) {
        // Only the class is logged, since the message could carry Product data.
        log.error(
                "Product panel unavailable: the database or audit write failed ({})",
                e.getClass().getName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
