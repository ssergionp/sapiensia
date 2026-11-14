package br.com.sergio.sapiensia.payment;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** The payment provider's Pix API. The copy-and-paste code is read on demand and never stored. */
public interface PixGateway {

    Optional<String> findCustomerCode(UUID customerId);

    /** Creates the customer at the provider and returns its code. */
    String createCustomer(UUID customerId, String name, String cpf);

    /** Creates the Pix charge and returns the charge id. */
    String createCharge(String customerCode, UUID orderId, int amountCents, LocalDate dueDate, String description);

    /** Returns the Pix copy-and-paste code of the charge. */
    String readCopyPaste(String chargeId);

    /**
     * Reads the charge as the provider has it now. Only a 404 fails as REFUSED (the charge does not exist); any other
     * 4xx fails as UNAVAILABLE so the read is retried.
     */
    PixCharge readCharge(String chargeId);

    /** Cancels the charge; a charge that no longer exists counts as cancelled. */
    void cancelCharge(String chargeId);
}
