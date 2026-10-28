package br.com.sergio.sapiensia.web;

import br.com.sergio.sapiensia.customer.PasswordCompromisedException;
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
        return ResponseEntity.badRequest().body(problem(errors));
    }

    @ExceptionHandler(PasswordCompromisedException.class)
    ResponseEntity<ProblemDetail> handlePasswordCompromised() {
        return ResponseEntity.badRequest().body(problem(Map.of("password", "PASSWORD_COMPROMISED")));
    }

    private static ProblemDetail problem(Map<String, String> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return problem;
    }
}
