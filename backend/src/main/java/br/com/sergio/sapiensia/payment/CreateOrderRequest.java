package br.com.sergio.sapiensia.payment;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * The body of {@code POST /api/orders}. There is no price field: an unknown JSON field is ignored (SEC-012). The CPF is
 * checked by the service, not by Bean Validation, whose messages could carry the refused value (SEC-033).
 */
record CreateOrderRequest(
        @NotNull(message = "PRODUCT_REQUIRED") UUID productId, String cpf) {

    // never the CPF: Spring logs the body object through toString at DEBUG
    @Override
    public String toString() {
        return "CreateOrderRequest[productId=" + productId + "]";
    }
}
