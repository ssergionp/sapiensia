package br.com.sergio.sapiensia.payment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Administrator's list of Refund requests (FR-023). Read only: the route needs REFUNDS_VIEW (SEC-029). */
@RestController
@RequestMapping("/api/admin/refund-requests")
class AdminRefundRequestController {

    private final RefundRequestService refunds;

    AdminRefundRequestController(RefundRequestService refunds) {
        this.refunds = refunds;
    }

    @GetMapping
    AdminRefundRequestPage list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(required = false) RefundRequestFilter status) {
        return refunds.listForAdministrator(page, status);
    }
}
