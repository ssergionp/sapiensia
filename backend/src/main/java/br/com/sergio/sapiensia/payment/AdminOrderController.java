package br.com.sergio.sapiensia.payment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Administrator's list of Orders (FR-020, SEC-010). Read only: the route needs ORDERS_VIEW. */
@RestController
@RequestMapping("/api/admin/orders")
class AdminOrderController {

    private final OrderService orders;

    AdminOrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    AdminOrderPage list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(required = false) OrderStatus status) {
        return orders.listForAdministrator(page, status);
    }
}
