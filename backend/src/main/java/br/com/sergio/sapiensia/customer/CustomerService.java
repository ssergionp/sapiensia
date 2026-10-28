package br.com.sergio.sapiensia.customer;

import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
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
     * <p>Not {@code @Transactional} on purpose: {@code save} runs in its own transaction, so the unique violation of a
     * concurrent registration is caught here, outside it, and nothing is left marked rollback-only.
     */
    public void register(String email, String rawPassword) {
        if (compromisedPasswordChecker.check(rawPassword).isCompromised()) {
            throw new PasswordCompromisedException();
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        // hash before looking up the e-mail, so the response time does not reveal whether the account exists
        String passwordHash = passwordEncoder.encode(rawPassword);
        if (customers.existsByEmail(normalizedEmail)) {
            return;
        }
        try {
            customers.save(new Customer(normalizedEmail, passwordHash));
        } catch (DataIntegrityViolationException duplicateEmail) {
            // lost the race against a concurrent registration of the same e-mail: same silent outcome
        }
    }
}
