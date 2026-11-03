package br.com.sergio.sapiensia.customer;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
class CustomerController {

    private final CustomerService customerService;

    CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    /** Public registration. Same 202 and empty body whether the e-mail is new or already registered. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    void register(@Valid @RequestBody RegisterCustomerRequest request) {
        customerService.register(request.email(), request.password());
    }

    /** Public e-mail confirmation: the link token plus the password chosen at registration. */
    @PostMapping("/email-confirmations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmEmail(@Valid @RequestBody ConfirmEmailRequest request) {
        customerService.confirmEmail(request.token(), request.password());
    }
}
