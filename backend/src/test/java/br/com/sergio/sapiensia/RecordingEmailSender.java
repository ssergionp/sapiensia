package br.com.sergio.sapiensia;

import br.com.sergio.sapiensia.email.EmailDeliveryException;
import br.com.sergio.sapiensia.email.EmailMessage;
import br.com.sergio.sapiensia.email.EmailSender;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test double that keeps every e-mail in memory instead of sending it. Shared by all tests of the same Spring context,
 * so each test must use its own recipient address.
 */
public class RecordingEmailSender implements EmailSender {

    private final List<EmailMessage> messages = new ArrayList<>();
    private final Map<String, Integer> returnedPerAddress = new HashMap<>();
    private final Set<String> failNextSendTo = ConcurrentHashMap.newKeySet();
    private final Set<String> failAllSendsTo = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> attemptsPerAddress = new ConcurrentHashMap<>();

    /** A send held inside {@code send} until the test releases it, to prove what happens while a send is in progress. */
    public static final class Blocker {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        /** True when a send reached the sender within the timeout. */
        public boolean awaitEntered(Duration timeout) throws InterruptedException {
            return entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** Lets the held send go on. Safe to call more than once. */
        public void release() {
            released.countDown();
        }
    }

    private final Map<String, Blocker> blockers = new ConcurrentHashMap<>();

    /** Holds the next send to this address (once) until {@link Blocker#release()}; the wait is bounded at 30 s. */
    public Blocker blockNextSendTo(String to) {
        Blocker blocker = new Blocker();
        blockers.put(to, blocker);
        return blocker;
    }

    @Override
    public void send(EmailMessage message) {
        attemptsPerAddress.merge(message.to(), 1, Integer::sum);
        Blocker blocker = blockers.remove(message.to());
        if (blocker != null) {
            blocker.entered.countDown();
            try {
                blocker.released.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failAllSendsTo.contains(message.to()) || failNextSendTo.remove(message.to())) {
            throw new EmailDeliveryException("Simulated delivery failure");
        }
        synchronized (this) {
            messages.add(message);
        }
    }

    /** Makes the next send to this address throw {@link EmailDeliveryException}, once. */
    public void failNextSendTo(String to) {
        failNextSendTo.add(to);
    }

    /** Makes every send to this address throw {@link EmailDeliveryException}. */
    public void failAllSendsTo(String to) {
        failAllSendsTo.add(to);
    }

    /** How many sends to this address were attempted, failed ones included. */
    public int attemptsTo(String to) {
        return attemptsPerAddress.getOrDefault(to, 0);
    }

    /** Every message recorded for this address, in arrival order (failed sends are not recorded). */
    public synchronized List<EmailMessage> messagesTo(String to) {
        return messages.stream().filter(m -> m.to().equals(to)).toList();
    }

    /** True while a failure set by {@link #failNextSendTo} has not been thrown yet. */
    public boolean hasPendingFailureFor(String to) {
        return failNextSendTo.contains(to);
    }

    /**
     * Waits for the next message to this address that no earlier call returned, in arrival order, polling every 100
     * ms.
     */
    public Optional<EmailMessage> awaitMessageTo(String to, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            synchronized (this) {
                int alreadyReturned = returnedPerAddress.getOrDefault(to, 0);
                List<EmailMessage> sentTo =
                        messages.stream().filter(m -> m.to().equals(to)).toList();
                if (sentTo.size() > alreadyReturned) {
                    returnedPerAddress.put(to, alreadyReturned + 1);
                    return Optional.of(sentTo.get(alreadyReturned));
                }
            }
            if (Instant.now().isAfter(deadline)) {
                return Optional.empty();
            }
            Thread.sleep(100);
        }
    }
}
