package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.customer.PasswordLogin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrator login and "who am I" of the panel. Logout is the existing {@code DELETE /api/sessions/current}. */
@RestController
@RequestMapping("/api/admin/sessions")
class AdminSessionController {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionController.class);

    // separate account keys from the customer's, so one kind of failure never locks the other; the IP bucket is shared
    private static final String ACCOUNT_KEY_PREFIX = "admin:";

    private final PasswordLogin passwordLogin;
    private final PasswordLogin.Variant loginVariant;

    AdminSessionController(
            Administrators administrators, PasswordEncoder passwordEncoder, PasswordLogin passwordLogin) {
        this.passwordLogin = passwordLogin;
        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(new AdministratorUserDetailsService(administrators));
        provider.setPasswordEncoder(passwordEncoder);
        // the whole session is replaced: new id and none of the previous attributes (a Customer who was logged in
        // at this browser is logged out)
        SessionFixationProtectionStrategy sessionStrategy = new SessionFixationProtectionStrategy();
        sessionStrategy.setMigrateSessionAttributes(false);
        this.loginVariant = new PasswordLogin.Variant(
                new ProviderManager(provider), sessionStrategy, ACCOUNT_KEY_PREFIX, "administrator", log, this::accept);
    }

    /** 204 and the session cookie, or an empty 401 that is identical for every kind of failure. */
    @PostMapping
    ResponseEntity<Void> login(
            @RequestBody AdminLoginRequest login, HttpServletRequest request, HttpServletResponse response) {
        return passwordLogin.login(loginVariant, login.email(), login.password(), request, response);
    }

    private Optional<Supplier<UserDetails>> accept(
            Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        AdministratorPrincipal principal =
                ((AdministratorUserDetailsService.Loaded) authentication.getPrincipal()).principal();
        return Optional.of(() -> principal);
    }

    @GetMapping("/current")
    Map<String, Object> current(Authentication authentication) {
        var principal = (AdministratorPrincipal) authentication.getPrincipal();
        List<String> capabilities = principal.capabilities().stream()
                .sorted(Comparator.naturalOrder())
                .map(Capability::name)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", principal.email());
        body.put("capabilities", capabilities);
        return body;
    }
}
