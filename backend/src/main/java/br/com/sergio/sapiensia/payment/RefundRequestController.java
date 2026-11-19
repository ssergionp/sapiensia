package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.customer.CurrentCustomer;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The logged-in Customer asks for the Refund of one of their paid Orders (FR-023). */
@RestController
class RefundRequestController {

    private final RefundRequestService refunds;

    RefundRequestController(RefundRequestService refunds) {
        this.refunds = refunds;
    }

    /** 201 for a new request, 200 and the stored date when the Order already had one. */
    @PostMapping(path = "/api/orders/{id}/refund-request", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RefundRequestResponse> request(
            @PathVariable UUID id, @Valid @RequestBody RefundRequestBody body, Authentication authentication) {
        RefundRequestService.Requested requested =
                refunds.request(CurrentCustomer.id(authentication), id, body.reason(), body.details());
        var response = new RefundRequestResponse(requested.requestedAt());
        if (requested.isNew()) {
            return ResponseEntity.created(URI.create("/api/orders/" + id + "/refund-request"))
                    .body(response);
        }
        return ResponseEntity.ok(response);
    }
}
