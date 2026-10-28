package br.com.sergio.sapiensia.customer;

import java.util.Locale;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final PasswordEncoder passwordEncoder;
    private final CompromisedPasswordChecker compromisedPasswordChecker;

    CustomerService(
            CustomerRepository customers,
            PasswordEncoder passwordEncoder,
            CompromisedPasswordChecker compromisedPasswordChecker) {
        this.customers = customers;
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
}
