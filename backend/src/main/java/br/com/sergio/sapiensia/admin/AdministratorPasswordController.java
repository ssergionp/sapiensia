package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.customer.InvalidConfirmationTokenException;
import br.com.sergio.sapiensia.customer.PasswordCompromisedException;
import br.com.sergio.sapiensia.customer.ResetPasswordRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public route: the single-use link in the body is the credential. Same rules and errors as the Customer reset. */
@RestController
@RequestMapping("/api/admin")
class AdministratorPasswordController {

    private final AdministratorPasswordTokens tokens;
    private final CompromisedPasswordChecker compromisedPasswordChecker;
    private final PasswordEncoder passwordEncoder;

    AdministratorPasswordController(
            AdministratorPasswordTokens tokens,
            CompromisedPasswordChecker compromisedPasswordChecker,
            PasswordEncoder passwordEncoder) {
        this.tokens = tokens;
        this.compromisedPasswordChecker = compromisedPasswordChecker;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Not transactional: the link and the password change in one statement, and Argon2 must not hold a connection. The
     * size rules ran in the request validation and the breach check runs here, both before the link is used, so a
     * rejected password keeps the link valid.
     */
    @PostMapping("/password-setup")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setPassword(@Valid @RequestBody ResetPasswordRequest request) {
        if (compromisedPasswordChecker.check(request.newPassword()).isCompromised()) {
            throw new PasswordCompromisedException("newPassword");
        }
        String passwordHash = passwordEncoder.encode(request.newPassword());
        tokens.consumeAndSetPassword(request.token(), passwordHash).orElseThrow(InvalidConfirmationTokenException::new);
    }
}
