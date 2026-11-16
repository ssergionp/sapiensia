package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.customer.CurrentCustomer;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The logged-in Customer's Orders: create one (or get the pending one back) and read it. */
@RestController
@RequestMapping("/api/orders")
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    /** 201 and Location for a new Order, 200 and the same body when the pending Order was reused. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrderCreated> create(@Valid @RequestBody CreateOrderRequest request, Authentication authentication) {
        OrderService.Created created =
                orders.create(CurrentCustomer.id(authentication), CurrentCustomer.email(authentication), request);
        var body = new OrderCreated(created.orderId());
        if (created.isNew()) {
            return ResponseEntity.created(URI.create("/api/orders/" + created.orderId()))
                    .body(body);
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping
    OrderListResponse list(Authentication authentication) {
        return orders.list(CurrentCustomer.id(authentication));
    }

    @GetMapping("/{id}")
    OrderResponse get(@PathVariable UUID id, Authentication authentication) {
        return orders.get(CurrentCustomer.id(authentication), id);
    }

    /** Only the state of the Order, for the page's polling; never calls the provider. */
    @GetMapping("/{id}/status")
    OrderStatusResponse status(@PathVariable UUID id, Authentication authentication) {
        return new OrderStatusResponse(orders.status(CurrentCustomer.id(authentication), id));
    }
}
