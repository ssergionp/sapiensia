package br.com.sergio.sapiensia.customer;

import br.com.sergio.sapiensia.web.RateLimitedOperation;
import br.com.sergio.sapiensia.web.RequestRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
class CustomerController {

    private final CustomerService customerService;

    private final RequestRateLimiter limiter;
    private final ClientIpResolver clientIp;

    CustomerController(CustomerService customerService, RequestRateLimiter limiter, ClientIpResolver clientIp) {
        this.customerService = customerService;
        this.limiter = limiter;
        this.clientIp = clientIp;
    }

    /** Public registration. Same 202 and empty body whether the e-mail is new or already registered. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    void register(@Valid @RequestBody RegisterCustomerRequest request, HttpServletRequest http) {
        // the limit counts the e-mail, so it runs once the body is read; the answer never says whether it exists
        limiter.require(
                RateLimitedOperation.SIGN_UP,
                clientIp.resolve(http),
                Optional.of(RequestRateLimiter.emailKey(request.email())));
        customerService.register(request.email(), request.password());
    }

    /** Public password reset request. Same 202 and empty body whether or not the account exists. */
    @PostMapping(path = "/password-resets", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestPasswordReset(@Valid @RequestBody RequestPasswordResetRequest request, HttpServletRequest http) {
        limiter.require(
                RateLimitedOperation.PASSWORD_RESET_REQUEST,
                clientIp.resolve(http),
                Optional.of(RequestRateLimiter.emailKey(request.email())));
        customerService.requestPasswordReset(request.email());
    }

    /** Public password change with the reset link. 204 and no login: the customer signs in afterwards. */
    @PostMapping(path = "/password-resets/confirmation", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        customerService.resetPassword(request.token(), request.newPassword());
    }

    /** Public e-mail confirmation: the link token plus the password chosen at registration. */
    @PostMapping(path = "/email-confirmations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmEmail(@Valid @RequestBody ConfirmEmailRequest request) {
        customerService.confirmEmail(request.token(), request.password());
    }
}
