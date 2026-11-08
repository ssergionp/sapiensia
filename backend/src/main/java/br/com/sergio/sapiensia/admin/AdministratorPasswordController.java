package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.audit.AuditAction;
import br.com.sergio.sapiensia.audit.AuditEntry;
import br.com.sergio.sapiensia.audit.AuditLog;
import br.com.sergio.sapiensia.audit.AuditObject;
import br.com.sergio.sapiensia.audit.AuditResult;
import br.com.sergio.sapiensia.customer.ClientIpResolver;
import br.com.sergio.sapiensia.customer.InvalidConfirmationTokenException;
import br.com.sergio.sapiensia.customer.PasswordCompromisedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public route: the single-use link in the body is the credential. Same rules and errors as the Customer reset. */
@RestController
@RequestMapping("/api/admin")
class AdministratorPasswordController {

    private static final Logger log = LoggerFactory.getLogger(AdministratorPasswordController.class);

    private final AdministratorPasswordTokens tokens;
    private final CompromisedPasswordChecker compromisedPasswordChecker;
    private final PasswordEncoder passwordEncoder;
    private final AuditLog audit;
    private final ClientIpResolver clientIp;
    private final TransactionTemplate transaction;

    AdministratorPasswordController(
            AdministratorPasswordTokens tokens,
            CompromisedPasswordChecker compromisedPasswordChecker,
            PasswordEncoder passwordEncoder,
            AuditLog audit,
            ClientIpResolver clientIp,
            PlatformTransactionManager transactionManager) {
        this.tokens = tokens;
        this.compromisedPasswordChecker = compromisedPasswordChecker;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clientIp = clientIp;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * The size rules ran in the request validation and the breach check and Argon2 run here, both before the link is
     * used and outside any transaction (Argon2 must not hold a connection), so a rejected password keeps the link
     * valid. Then one short transaction uses the link, sets the password and audits ADMIN_PASSWORD_SET: if the event
     * cannot be written, nothing changes and the answer is 503. An invalid link is not audited.
     */
    @PostMapping("/password-setup")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setPassword(@Valid @RequestBody AdminPasswordSetupRequest request, HttpServletRequest http) {
        if (compromisedPasswordChecker.check(request.newPassword()).isCompromised()) {
            throw new PasswordCompromisedException("newPassword");
        }
        String passwordHash = passwordEncoder.encode(request.newPassword());
        String origin = clientIp.resolve(http);
        Optional<UUID> administratorId;
        try {
            administratorId = transaction.execute(status -> {
                Optional<UUID> id = tokens.consumeAndSetPassword(request.token(), passwordHash);
                id.ifPresent(value -> audit.recordInTransaction(new AuditEntry(
                        AuditAction.ADMIN_PASSWORD_SET,
                        AuditResult.SUCCESS,
                        value,
                        AuditObject.ADMINISTRATOR,
                        value,
                        origin)));
                return id;
            });
        } catch (DataAccessException | TransactionException e) {
            // only the class: the message could carry the statement's parameters
            log.error("Password setup unavailable ({})", e.getClass().getName());
            throw new AuditUnavailableException();
        }
        administratorId.orElseThrow(InvalidConfirmationTokenException::new);
    }

    @ExceptionHandler(AuditUnavailableException.class)
    ResponseEntity<Void> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
