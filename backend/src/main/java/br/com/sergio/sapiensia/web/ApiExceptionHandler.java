package br.com.sergio.sapiensia.web;

import br.com.sergio.sapiensia.admin.InvalidMfaCodeException;
import br.com.sergio.sapiensia.customer.IncorrectConfirmationPasswordException;
import br.com.sergio.sapiensia.customer.InvalidConfirmationTokenException;
import br.com.sergio.sapiensia.customer.PasswordCompromisedException;
import br.com.sergio.sapiensia.customer.WrongPasswordLimitReachedException;
import br.com.sergio.sapiensia.payment.InvalidWebhookBodyException;
import br.com.sergio.sapiensia.product.ProductFileRequiredException;
import br.com.sergio.sapiensia.product.ProductNotFoundException;
import br.com.sergio.sapiensia.product.ProductVersionConflictException;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Turns validation and business errors into {@code ProblemDetail} with an {@code errors} map of field to code. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // a field may fail several constraints: keep one code per field, the first alphabetically, so it is stable
        Map<String, String> errors = new TreeMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.merge(
                        error.getField(),
                        String.valueOf(error.getDefaultMessage()),
                        (a, b) -> a.compareTo(b) <= 0 ? a : b));
        // a bad confirmation token hides every other error, so the answer never says anything about the password
        if (errors.containsKey("token")) {
            errors.keySet().removeIf(field -> !field.equals("token"));
        }
        return ResponseEntity.badRequest().body(problem(errors));
    }

    @ExceptionHandler(PasswordCompromisedException.class)
    ResponseEntity<ProblemDetail> handlePasswordCompromised(PasswordCompromisedException e) {
        return ResponseEntity.badRequest().body(problem(Map.of(e.field(), "PASSWORD_COMPROMISED")));
    }

    @ExceptionHandler(InvalidConfirmationTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidConfirmationToken() {
        return ResponseEntity.badRequest().body(problem(Map.of("token", "TOKEN_INVALID")));
    }

    @ExceptionHandler(WrongPasswordLimitReachedException.class)
    ResponseEntity<ProblemDetail> handleWrongPasswordLimitReached() {
        return ResponseEntity.badRequest().body(problem(Map.of("token", "TOKEN_LOCKED")));
    }

    @ExceptionHandler(IncorrectConfirmationPasswordException.class)
    ResponseEntity<ProblemDetail> handleIncorrectConfirmationPassword() {
        return ResponseEntity.badRequest().body(problem(Map.of("password", "PASSWORD_INCORRECT")));
    }

    @ExceptionHandler(InvalidMfaCodeException.class)
    ResponseEntity<ProblemDetail> handleInvalidMfaCode() {
        return ResponseEntity.badRequest().body(problem(Map.of("code", "CODE_INVALID")));
    }

    @ExceptionHandler(InvalidWebhookBodyException.class)
    ResponseEntity<ProblemDetail> handleInvalidWebhookBody() {
        return ResponseEntity.badRequest().body(problem(Map.of("body", "BODY_INVALID")));
    }

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<Void> handleProductNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(ProductVersionConflictException.class)
    ResponseEntity<Void> handleProductVersionConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /** Publishing needs the Product file (FR-011): 409 with the code of the field, like the other refusals. */
    @ExceptionHandler(ProductFileRequiredException.class)
    ResponseEntity<ProblemDetail> handleProductFileRequired() {
        ProblemDetail problem = problem(Map.of("file", "FILE_REQUIRED"));
        problem.setStatus(HttpStatus.CONFLICT);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    private static ProblemDetail problem(Map<String, String> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return problem;
    }
}
