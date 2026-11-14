package br.com.sergio.sapiensia.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Local development, CI and tests: keeps everything in memory and never calls the network. Never for production. */
@Component
@ConditionalOnProperty(name = "app.pix.provider", havingValue = "fake")
class FakePixGateway implements PixGateway {

    private final Map<UUID, String> customerCodes = new ConcurrentHashMap<>();
    private final Map<String, PixCharge> charges = new ConcurrentHashMap<>();

    FakePixGateway(Environment environment) {
        // a fake charge can never be paid: production must use the real provider
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("app.pix.provider=fake is not allowed with the prod profile");
        }
    }

    @Override
    public Optional<String> findCustomerCode(UUID customerId) {
        return Optional.ofNullable(customerCodes.get(customerId));
    }

    @Override
    public String createCustomer(UUID customerId, String name, String cpf) {
        // only the generated code is kept, never the CPF
        return customerCodes.computeIfAbsent(customerId, id -> "cus_fake_" + UUID.randomUUID());
    }

    @Override
    public String createCharge(
            String customerCode, UUID orderId, int amountCents, LocalDate dueDate, String description) {
        String id = "pay_fake_" + UUID.randomUUID().toString().replace("-", "");
        charges.put(
                id, new PixCharge(id, "PENDING", BigDecimal.valueOf(amountCents, 2), "PIX", orderId.toString(), false));
        return id;
    }

    @Override
    public PixCharge readCharge(String chargeId) {
        PixCharge charge = charges.get(chargeId);
        if (charge == null) {
            throw new PixGatewayException(PixGatewayException.Kind.REFUSED, "READ_CHARGE", "HTTP 404");
        }
        // a fake charge counts as paid as soon as it is read (decision D11)
        return new PixCharge(
                charge.id(), "RECEIVED", charge.value(), "PIX", charge.externalReference(), charge.deleted());
    }

    @Override
    public String readCopyPaste(String chargeId) {
        return "PIX-FICTICIO-NAO-PAGAVEL-" + chargeId;
    }

    @Override
    public void cancelCharge(String chargeId) {
        charges.computeIfPresent(
                chargeId,
                (id, c) -> new PixCharge(c.id(), c.status(), c.value(), c.billingType(), c.externalReference(), true));
    }
}
