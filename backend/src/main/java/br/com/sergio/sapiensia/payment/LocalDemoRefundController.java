package br.com.sergio.sapiensia.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo only (#132): marks a charge of the in-memory fake provider as refunded, so the real refund flow (webhook, read
 * of the charge, RefundConfirmation) can run locally. Exists only with the fake provider; guarded like the webhook.
 */
@RestController
@ConditionalOnProperty(name = "app.pix.provider", havingValue = "fake")
class LocalDemoRefundController {

    private final FakePixGateway gateway;

    LocalDemoRefundController(FakePixGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/api/local-demo/charges/{chargeId}/refund")
    ResponseEntity<Void> refund(@PathVariable String chargeId) {
        return gateway.refund(chargeId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
