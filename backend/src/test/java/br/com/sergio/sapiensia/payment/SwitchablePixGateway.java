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
    private final AtomicReference<Runnable> afterCancel = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> heldRead = new AtomicReference<>();
    private final Map<String, PixCharge> answers = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, PixCharge>> threadAnswers = ThreadLocal.withInitial(ConcurrentHashMap::new);
    private volatile PixGatewayException.Kind failingCancel;
    private volatile String failingCancelCharge;
    private volatile String lastCustomerCpf;
    private volatile String lastCustomerName;
    private volatile Integer lastChargeAmountCents;
    private volatile UUID lastChargeOrderId;
    private volatile String lastChargeDescription;

    SwitchablePixGateway(PixGateway delegate) {
        this.delegate = delegate;
    }

    /** Every call, in order, by step (FIND_CUSTOMER, CREATE_CUSTOMER, CREATE_CHARGE, READ_QR_CODE, READ_CHARGE, CANCEL_CHARGE). */
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
        this.failingCancel = failing ? PixGatewayException.Kind.UNAVAILABLE : null;
    }

    /** Makes {@code cancelCharge} fail with the kind; a null kind makes it work again. */
    public void failCancel(PixGatewayException.Kind kind) {
        this.failingCancel = kind;
    }

    /** Makes {@code cancelCharge} of this one charge fail as unavailable; null makes it work again. */
    public void failCancelOf(String chargeId) {
        this.failingCancelCharge = chargeId;
    }

    /** The next {@code createCharge} records itself and then waits for the latch before it creates the charge. */
    public void holdNextCharge(CountDownLatch latch) {
        held.set(latch);
    }

    /** Runs the code right after the next charge was created, before {@code createCharge} returns. */
    public void afterNextCharge(Runnable action) {
        afterCharge.set(action);
    }

    /** Runs the code right after the next {@code cancelCharge} was recorded and answered, before it returns. */
    public void afterNextCancel(Runnable action) {
        afterCancel.set(action);
    }

    public void reset() {
        afterCancel.set(null);
        calls.clear();
        cancelled.clear();
        chargeIds.clear();
        failures.clear();
        held.set(null);
        afterCharge.set(null);
        heldRead.set(null);
        answers.clear();
        threadAnswers.remove();
        failingCancel = null;
        failingCancelCharge = null;
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

    /** Makes {@code readCharge} of this one charge answer with the given charge; {@link #reset()} clears it. */
    public void answerCharge(String chargeId, PixCharge charge) {
        answers.put(chargeId, charge);
    }

    /**
     * Like {@link #answerCharge} but only for the calling thread, so two tasks reading the same charge at the same
     * moment can see different states. Clear it with {@link #forgetThreadAnswers()} before the thread is reused.
     */
    public void answerChargeOnThisThread(String chargeId, PixCharge charge) {
        threadAnswers.get().put(chargeId, charge);
    }

    public void forgetThreadAnswers() {
        threadAnswers.remove();
    }

    /** The next {@code readCharge} records itself and then waits for the latch before it reads the charge. */
    public void holdNextRead(CountDownLatch latch) {
        heldRead.set(latch);
    }

    @Override
    public PixCharge readCharge(String chargeId) {
        enter("READ_CHARGE");
        CountDownLatch latch = heldRead.getAndSet(null);
        if (latch != null) {
            try {
                if (!latch.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("held read was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("held read interrupted");
            }
        }
        PixCharge fixed = threadAnswers.get().get(chargeId);
        if (fixed == null) {
            fixed = answers.get(chargeId);
        }
        return fixed != null ? fixed : delegate.readCharge(chargeId);
    }

    @Override
    public void cancelCharge(String chargeId) {
        calls.add("CANCEL_CHARGE");
        cancelled.add(chargeId);
        PixGatewayException.Kind failure = failingCancel;
        if (failure == null && chargeId.equals(failingCancelCharge)) {
            failure = PixGatewayException.Kind.UNAVAILABLE;
        }
        if (failure != null) {
            throw new PixGatewayException(failure, "CANCEL_CHARGE", "HTTP 503");
        }
        delegate.cancelCharge(chargeId);
        Runnable action = afterCancel.getAndSet(null);
        if (action != null) {
            action.run();
        }
    }
}
