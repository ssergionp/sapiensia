package br.com.sergio.sapiensia.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers 400 for a body that cannot be read, exactly as the inherited handler of {@link ApiExceptionHandler} did. It
 * is a separate advice, ahead of that one, because the inherited method takes a {@code WebRequest}: Spring logs the
 * arguments of an exception handler at TRACE, and {@code ServletWebRequest#toString} prints the session user (the
 * Customer e-mail). This method takes no argument, so nothing of the request is logged (SEC-033).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class UnreadableBodyHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableBody() {
        // the body stream gave up because it went over 64 KiB (RequestBodyLimitFilter): a 413, not a 400
        if (RequestBodyLimitFilter.exceeded()) {
            return payloadTooLarge();
        }
        return ResponseEntity.badRequest()
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Failed to read request"));
    }

    static ResponseEntity<ProblemDetail> payloadTooLarge() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        problem.setTitle("Payload too large");
        problem.setProperty("code", "PAYLOAD_TOO_LARGE");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }
}
