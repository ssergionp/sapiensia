package br.com.sergio.sapiensia.payment;

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
        return "pay_fake_" + UUID.randomUUID();
    }

    @Override
    public String readCopyPaste(String chargeId) {
        return "PIX-FICTICIO-NAO-PAGAVEL-" + chargeId;
    }

    @Override
    public void cancelCharge(String chargeId) {
        // nothing to cancel in memory
    }
}
