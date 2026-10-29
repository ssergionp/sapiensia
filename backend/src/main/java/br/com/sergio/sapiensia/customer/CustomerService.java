package br.com.sergio.sapiensia.customer;

import java.util.Locale;
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

    CustomerService(
            CustomerRepository customers,
            EmailConfirmationTokens confirmationTokens,
            PasswordEncoder passwordEncoder,
            CompromisedPasswordChecker compromisedPasswordChecker) {
        this.customers = customers;
        this.confirmationTokens = confirmationTokens;
        this.passwordEncoder = passwordEncoder;
        this.compromisedPasswordChecker = compromisedPasswordChecker;
    }

    /**
     * Creates an unconfirmed account. A repeated e-mail is silently ignored so the caller cannot tell whether the
     * account exists.
     *
     * <p>Not {@code @Transactional}: the single insert is atomic and runs in its own transaction.
     */
    public void register(String email, String rawPassword) {
        if (compromisedPasswordChecker.check(rawPassword).isCompromised()) {
            throw new PasswordCompromisedException();
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        // the password is hashed on both paths (new and repeated e-mail), so the response time stays equal
        String passwordHash = passwordEncoder.encode(rawPassword);
        // a repeated e-mail inserts nothing (on conflict do nothing): no exception, so no e-mail in the database log
        customers.insertIfAbsent(normalizedEmail, passwordHash);
    }

    /**
     * Confirms the e-mail if the link is valid and the password matches the account. A wrong password keeps the link
     * usable, except on the {@value #MAX_WRONG_PASSWORDS}th wrong one, which deletes it.
     *
     * <p>Not {@code @Transactional}: each statement is atomic on its own, and Argon2 must not hold a connection.
     */
    public void confirmEmail(String token, String rawPassword) {
        EmailConfirmationTokens.Pending pending =
                confirmationTokens.findValid(token).orElseThrow(InvalidConfirmationTokenException::new);
        if (!passwordEncoder.matches(rawPassword, pending.passwordHash())) {
            int attempts = confirmationTokens
                    .recordWrongPassword(pending.tokenId())
                    .orElseThrow(InvalidConfirmationTokenException::new);
            if (attempts >= MAX_WRONG_PASSWORDS) {
                confirmationTokens.delete(pending.tokenId());
                throw new InvalidConfirmationTokenException();
            }
            throw new IncorrectConfirmationPasswordException();
        }
        if (!confirmationTokens.consumeAndConfirm(pending.tokenId())) {
            throw new InvalidConfirmationTokenException();
        }
    }
}
