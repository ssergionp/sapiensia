package br.com.sergio.sapiensia.customer;

import br.com.sergio.sapiensia.admin.AdministratorPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymizes a Customer account (FR-036, SEC-038). The lock (capability, both factors, recent authentication) is
 * CustomerAnonymizationAccess in the SecurityConfig. Neither the e-mail nor anything derived from it is logged.
 */
@RestController
@RequestMapping("/api/admin/customer-anonymizations")
class CustomerAnonymizationController {

    private final CustomerAnonymization anonymization;
    private final ClientIpResolver clientIp;

    CustomerAnonymizationController(CustomerAnonymization anonymization, ClientIpResolver clientIp) {
        this.anonymization = anonymization;
        this.clientIp = clientIp;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Anonymize the account that has this e-mail")
    @ApiResponse(responseCode = "204", description = "Anonymized")
    @ApiResponse(responseCode = "400", description = "Invalid e-mail")
    @ApiResponse(responseCode = "401", description = "Not logged in with both factors")
    @ApiResponse(responseCode = "403", description = "No capability, or REAUTHENTICATION_REQUIRED")
    @ApiResponse(responseCode = "409", description = "CUSTOMER_HAS_PENDING_ORDER: wait for the Order to expire")
    @ApiResponse(responseCode = "404", description = "CUSTOMER_NOT_FOUND: unknown or already anonymized")
    ResponseEntity<Void> anonymize(
            @Valid @RequestBody CustomerAnonymizationRequest body,
            Authentication authentication,
            HttpServletRequest request) {
        var administrator = (AdministratorPrincipal) authentication.getPrincipal();
        String origin = clientIp.resolve(request);
        anonymization.anonymize(body.email(), administrator.id(), origin).orElseThrow(CustomerNotFoundException::new);
        return ResponseEntity.noContent().build();
    }
}
