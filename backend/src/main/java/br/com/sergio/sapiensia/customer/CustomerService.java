package br.com.sergio.sapiensia.customer;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Duration;
import java.time.Instant;
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
    private final PasswordResetTokens passwordResetTokens;
    private final PasswordEncoder passwordEncoder;
    private final CompromisedPasswordChecker compromisedPasswordChecker;
    private final SchedulerClient scheduler;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final KnownDevices knownDevices;
    private final Duration unconfirmedRetention;

    CustomerService(
            CustomerRepository customers,
            EmailConfirmationTokens confirmationTokens,
            PasswordResetTokens passwordResetTokens,
            PasswordEncoder passwordEncoder,
            CompromisedPasswordChecker compromisedPasswordChecker,
            SchedulerClient scheduler,
            LoginAttemptLimiter loginAttemptLimiter,
            KnownDevices knownDevices,
            @Value("${app.unconfirmed-customer.retention}") Duration unconfirmedRetention) {
        this.unconfirmedRetention = unconfirmedRetention;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.knownDevices = knownDevices;
        this.customers = customers;
        this.confirmationTokens = confirmationTokens;
        this.passwordResetTokens = passwordResetTokens;
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
        String normalizedEmail = Emails.normalize(email);
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
     * Schedules the reset-link task and nothing else, so the response is the same, and takes the same time, whether
     * the e-mail belongs to a confirmed account or not. Without an account the task gets a random id it will not find.
     */
    public void requestPasswordReset(String email) {
        String normalizedEmail = Emails.normalize(email);
        UUID customerId = customers.findConfirmedIdByEmail(normalizedEmail).orElseGet(UUID::randomUUID);
        schedule(CustomerEmailTasks.PASSWORD_RESET, customerId);
    }

    /**
     * Sets a new password with a single-use link, lifts the account's login lockout and schedules the notice to the
     * owner. Does not log in and does not confirm the e-mail.
     *
     * <p>Not {@code @Transactional}: the link and the password change in one statement, and Argon2 must not hold a
     * connection. The size rules ran before, in the request validation, so a rejected password keeps the link valid.
     */
    public void resetPassword(String token, String rawPassword) {
        if (compromisedPasswordChecker.check(rawPassword).isCompromised()) {
            throw new PasswordCompromisedException("newPassword");
        }
        String passwordHash = passwordEncoder.encode(rawPassword);
        UUID customerId = passwordResetTokens
                .consumeAndSetPassword(token, passwordHash)
                .orElseThrow(InvalidConfirmationTokenException::new);
        // the owner proved they hold the e-mail: lift the account lockout (SEC-002), never the per-IP limit
        customers
                .findEmailById(customerId)
                .ifPresent(email -> loginAttemptLimiter.releaseAccount(LoginAttemptLimiter.customerKey(email)));
        schedule(CustomerEmailTasks.PASSWORD_CHANGED_NOTICE, customerId);
    }

    /** The e-mail address of the account, or empty when it does not exist (anymore). */
    public java.util.Optional<String> findEmail(UUID customerId) {
        return customers.findEmailById(customerId);
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

    /**
     * Registers the device of a correct login and, if the account has logged in before from another device, schedules
     * the e-mail to its owner. {@code cookieToken} is null when the browser sent no device cookie.
     *
     * @return the token for the device cookie
     */
    public String recognizeDevice(UUID customerId, String cookieToken, Instant loggedInAt) {
        // not atomic: if scheduling throws after recognize(), the device row and first_login_at stay recorded, the
        // cookie is not written and the notice is lost. Login still succeeds by design.
        KnownDevices.DeviceCheck check = knownDevices.recognize(customerId, cookieToken);
        if (check.notifyOwner()) {
            scheduler.schedule(
                    new TaskInstance<>(
                            CustomerEmailTasks.NEW_DEVICE_NOTICE,
                            UUID.randomUUID().toString(),
                            new NewDeviceNotice(customerId, loggedInAt)),
                    Instant.now());
        }
        return check.token();
    }

    /** Ends every session of the account, the current one included (SEC-009, SEC-006). */
    public void logoutEverywhere(UUID customerId) {
        customers.incrementSessionVersion(customerId);
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
                throw new WrongPasswordLimitReachedException();
            }
            throw new IncorrectConfirmationPasswordException();
        }
        // the hash is re-checked in the same statement: a password replaced during Argon2 must not be confirmed
        if (!confirmationTokens.consumeAndConfirm(pending.tokenId(), pending.passwordHash())) {
            throw new InvalidConfirmationTokenException();
        }
    }
}
