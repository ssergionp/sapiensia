package br.com.sergio.sapiensia.library;

import br.com.sergio.sapiensia.customer.CurrentCustomer;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Library of the logged-in Customer (FR-029): what they bought and still may use. */
@RestController
@RequestMapping("/api/library")
class LibraryController {

    record LibraryResponse(List<LibraryItem> items) {}

    private final LibraryItems items;

    LibraryController(LibraryItems items) {
        this.items = items;
    }

    @GetMapping
    LibraryResponse list(Authentication authentication) {
        return new LibraryResponse(items.of(CurrentCustomer.id(authentication)));
    }
}
