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

/**
 * Test double that keeps every e-mail in memory instead of sending it. Shared by all tests of the same Spring context,
 * so each test must use its own recipient address.
 */
public class RecordingEmailSender implements EmailSender {

    private final List<EmailMessage> messages = new ArrayList<>();
    private final Map<String, Integer> returnedPerAddress = new HashMap<>();
    private final Set<String> failNextSendTo = ConcurrentHashMap.newKeySet();

    @Override
    public void send(EmailMessage message) {
        if (failNextSendTo.remove(message.to())) {
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
