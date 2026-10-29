package br.com.sergio.sapiensia.customer;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {

    private static final int MAX_WRONG_PASSWORDS = 5;

    private final CustomerRepository customers;
    private final EmailConfirmationTokens confirmationTokens;
    private final PasswordEncoder passwordEncoder;
    private final CompromisedPasswordChecker compromisedPasswordChecker;
    private final SchedulerClient scheduler;
    private final Duration unconfirmedRetention;

    CustomerService(
            CustomerRepository customers,
            EmailConfirmationTokens confirmationTokens,
            PasswordEncoder passwordEncoder,
            CompromisedPasswordChecker compromisedPasswordChecker,
            SchedulerClient scheduler,
            @Value("${app.unconfirmed-customer.retention}") Duration unconfirmedRetention) {
        this.unconfirmedRetention = unconfirmedRetention;
        this.customers = customers;
        this.confirmationTokens = confirmationTokens;
        this.passwordEncoder = passwordEncoder;
        this.compromisedPasswordChecker = compromisedPasswordChecker;
        this.scheduler = scheduler;
    }

    /**
     * Registers the e-mail and schedules exactly one e-mail, so the caller cannot tell whether the account exists: a
     * new account gets a confirmation link; an unconfirmed one gets the new password, loses its old links and gets a
     * new link; a confirmed one is left unchanged and its owner gets a notice.
     *
     * <p>Not {@code @Transactional}: each statement is atomic, and Argon2 must not hold a connection. The e-mail is
     * only scheduled, so registration never depends on the delivery.
     */
    public void register(String email, String rawPassword) {
        if (compromisedPasswordChecker.check(rawPassword).isCompromised()) {
            throw new PasswordCompromisedException();
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        // the password is hashed on every path, so the response time stays equal
        String passwordHash = passwordEncoder.encode(rawPassword);
        // a repeated e-mail inserts nothing (on conflict do nothing): no exception, so no e-mail in the database log
        if (customers.insertIfAbsent(normalizedEmail, passwordHash) == 1) {
            customers.findIdByEmail(normalizedEmail).ifPresent(id -> schedule(CustomerEmailTasks.CONFIRMATION, id));
            return;
        }
        Optional<UUID> unconfirmed = customers.replacePasswordIfUnconfirmed(normalizedEmail, passwordHash);
        if (unconfirmed.isPresent()) {
            // not atomic with the password change on purpose: an old link only works with the current password anyway
            confirmationTokens.revokeAll(unconfirmed.get());
            schedule(CustomerEmailTasks.CONFIRMATION, unconfirmed.get());
            return;
        }
        customers
                .findIdByEmail(normalizedEmail)
                .ifPresent(id -> schedule(CustomerEmailTasks.ACCOUNT_EXISTS_NOTICE, id));
    }

    /**
     * Deletes the accounts whose e-mail stayed unconfirmed for longer than the retention (counted from creation);
     * their links go with them. Frees the e-mail for a new registration.
     *
     * @return how many accounts were deleted
     */
    public int deleteUnconfirmedOlderThanRetention() {
        return customers.deleteUnconfirmedCreatedBefore(Instant.now().minus(unconfirmedRetention));
    }

    private void schedule(String taskName, UUID customerId) {
        scheduler.schedule(new TaskInstance<>(taskName, UUID.randomUUID().toString(), customerId), Instant.now());
    }

    /**
     * Confirms the e-mail if the link is valid and the password matches the account. A wrong password keeps the link
     * usable, except on the {@value #MAX_WRONG_PASSWORDS}th wrong one, which deletes it.
     *
     * <p>Not {@code @Transactional}: each statement is atomic on its own, and Argon2 must not hold a connection.
     */
    public void confirmEmail(String token, String rawPassword) {
        // the attempt is reserved atomically before the slow check, so parallel guesses cannot exceed the limit
        EmailConfirmationTokens.Pending pending = confirmationTokens
                .reserveAttempt(token, MAX_WRONG_PASSWORDS)
                .orElseThrow(InvalidConfirmationTokenException::new);
        if (!passwordEncoder.matches(rawPassword, pending.passwordHash())) {
            if (pending.attempts() >= MAX_WRONG_PASSWORDS) {
                confirmationTokens.delete(pending.tokenId());
                throw new InvalidConfirmationTokenException();
            }
            throw new IncorrectConfirmationPasswordException();
        }
        // the hash is re-checked in the same statement: a password replaced during Argon2 must not be confirmed
        if (!confirmationTokens.consumeAndConfirm(pending.tokenId(), pending.passwordHash())) {
            throw new InvalidConfirmationTokenException();
        }
    }
}
