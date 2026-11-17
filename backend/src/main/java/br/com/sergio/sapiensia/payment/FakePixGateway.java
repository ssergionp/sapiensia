package br.com.sergio.sapiensia.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final Set<String> refunded = ConcurrentHashMap.newKeySet();
    private final Map<String, PixCharge> charges = Collections.synchronizedMap(new LinkedHashMap<>());

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
        // a fake charge counts as paid as soon as it is read (decision D11), until the demo refunds it (#132)
        String status = refunded.contains(chargeId) ? "REFUNDED" : "RECEIVED";
        return new PixCharge(charge.id(), status, charge.value(), "PIX", charge.externalReference(), charge.deleted());
    }

    /** Demo only (#132): from now on the charge reads as REFUNDED; false when the fake does not know it. */
    boolean refund(String chargeId) {
        if (!charges.containsKey(chargeId)) {
            return false;
        }
        refunded.add(chargeId);
        return true;
    }

    @Override
    public PixChargePage listCharges(LocalDate createdFrom, int offset, int limit) {
        // the stored state (PENDING), in creation order; createdFrom is ignored because the fake keeps no dates
        List<PixCharge> all;
        synchronized (charges) {
            all = List.copyOf(charges.values());
        }
        int from = Math.min(Math.max(offset, 0), all.size());
        int to = Math.min(from + Math.max(limit, 0), all.size());
        return new PixChargePage(all.subList(from, to), to < all.size());
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
