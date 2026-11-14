package br.com.sergio.sapiensia.payment;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test double of the Pix gateway: it delegates to the in-memory fake, remembers every call and lets a test make a
 * step fail, hold the next charge or run code right after it. {@link #reset()} clears all of it.
 */
public class SwitchablePixGateway implements PixGateway {

    private final PixGateway delegate;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> cancelled = new CopyOnWriteArrayList<>();
    private final List<String> chargeIds = new CopyOnWriteArrayList<>();
    private final Map<String, PixGatewayException.Kind> failures = new ConcurrentHashMap<>();
    private final AtomicReference<CountDownLatch> held = new AtomicReference<>();
    private final AtomicReference<Runnable> afterCharge = new AtomicReference<>();
    private volatile boolean failingCancel;
    private volatile String lastCustomerCpf;
    private volatile String lastCustomerName;
    private volatile Integer lastChargeAmountCents;
    private volatile UUID lastChargeOrderId;
    private volatile String lastChargeDescription;

    SwitchablePixGateway(PixGateway delegate) {
        this.delegate = delegate;
    }

    /** Every call, in order, by step (FIND_CUSTOMER, CREATE_CUSTOMER, CREATE_CHARGE, READ_QR_CODE, CANCEL_CHARGE). */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    public long count(String step) {
        return calls.stream().filter(step::equals).count();
    }

    public String lastCustomerCpf() {
        return lastCustomerCpf;
    }

    public String lastCustomerName() {
        return lastCustomerName;
    }

    public Integer lastChargeAmountCents() {
        return lastChargeAmountCents;
    }

    public UUID lastChargeOrderId() {
        return lastChargeOrderId;
    }

    public String lastChargeDescription() {
        return lastChargeDescription;
    }

    public String lastChargeId() {
        return chargeIds.isEmpty() ? null : chargeIds.get(chargeIds.size() - 1);
    }

    public long copyPasteReads() {
        return count("READ_QR_CODE");
    }

    /** The charge ids {@code cancelCharge} was asked for, even when the cancel itself failed. */
    public List<String> cancelledCharges() {
        return List.copyOf(cancelled);
    }

    /** Makes every call of the step fail with the kind; a null kind makes it work again. */
    public void failAt(String step, PixGatewayException.Kind kind) {
        if (kind == null) {
            failures.remove(step);
        } else {
            failures.put(step, kind);
        }
    }

    public void failCancel(boolean failing) {
        this.failingCancel = failing;
    }

    /** The next {@code createCharge} records itself and then waits for the latch before it creates the charge. */
    public void holdNextCharge(CountDownLatch latch) {
        held.set(latch);
    }

    /** Runs the code right after the next charge was created, before {@code createCharge} returns. */
    public void afterNextCharge(Runnable action) {
        afterCharge.set(action);
    }

    public void reset() {
        calls.clear();
        cancelled.clear();
        chargeIds.clear();
        failures.clear();
        held.set(null);
        afterCharge.set(null);
        failingCancel = false;
        lastCustomerCpf = null;
        lastCustomerName = null;
        lastChargeAmountCents = null;
        lastChargeOrderId = null;
        lastChargeDescription = null;
    }

    private void enter(String step) {
        calls.add(step);
        PixGatewayException.Kind kind = failures.get(step);
        if (kind != null) {
            throw new PixGatewayException(kind, step, "HTTP 503");
        }
    }

    @Override
    public Optional<String> findCustomerCode(UUID customerId) {
        enter("FIND_CUSTOMER");
        return delegate.findCustomerCode(customerId);
    }

    @Override
    public String createCustomer(UUID customerId, String name, String cpf) {
        enter("CREATE_CUSTOMER");
        lastCustomerCpf = cpf;
        lastCustomerName = name;
        return delegate.createCustomer(customerId, name, cpf);
    }

    @Override
    public String createCharge(
            String customerCode, UUID orderId, int amountCents, LocalDate dueDate, String description) {
        enter("CREATE_CHARGE");
        lastChargeAmountCents = amountCents;
        lastChargeOrderId = orderId;
        lastChargeDescription = description;
        CountDownLatch latch = held.getAndSet(null);
        if (latch != null) {
            try {
                if (!latch.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("held charge was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("held charge interrupted");
            }
        }
        String id = delegate.createCharge(customerCode, orderId, amountCents, dueDate, description);
        chargeIds.add(id);
        Runnable action = afterCharge.getAndSet(null);
        if (action != null) {
            action.run();
        }
        return id;
    }

    @Override
    public String readCopyPaste(String chargeId) {
        enter("READ_QR_CODE");
        return delegate.readCopyPaste(chargeId);
    }

    @Override
    public void cancelCharge(String chargeId) {
        calls.add("CANCEL_CHARGE");
        cancelled.add(chargeId);
        if (failingCancel) {
            throw new PixGatewayException(PixGatewayException.Kind.UNAVAILABLE, "CANCEL_CHARGE", "HTTP 503");
        }
        delegate.cancelCharge(chargeId);
    }
}
