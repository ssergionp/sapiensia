package br.com.sergio.sapiensia.product;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public Catalog (#20, ADR-0007): open to any Visitor, GET only (see SecurityConfig). There is no handler of its
 * own: an unknown, draft or unpublished id is the empty 404 of ProductNotFoundException.
 */
@RestController
@RequestMapping("/api/products")
class CatalogController {

    private final CatalogService service;

    CatalogController(CatalogService service) {
        this.service = service;
    }

    /** 200 with a page of 20; 400 for a negative or non-numeric page; a page past the end is empty. */
    @GetMapping
    CatalogPage list(@RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page) {
        return service.list(page);
    }

    /** 200 for a published Product; 404 for any other; 400 when the id is not a UUID. */
    @GetMapping("/{id}")
    CatalogProduct get(@PathVariable UUID id) {
        return service.get(id);
    }
}
