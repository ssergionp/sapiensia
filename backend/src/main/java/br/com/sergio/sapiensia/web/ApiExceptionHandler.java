package br.com.sergio.sapiensia.web;

import br.com.sergio.sapiensia.admin.InvalidMfaCodeException;
import br.com.sergio.sapiensia.admin.InvalidReauthenticationException;
import br.com.sergio.sapiensia.customer.CustomerNotFoundException;
import br.com.sergio.sapiensia.customer.IncorrectConfirmationPasswordException;
import br.com.sergio.sapiensia.customer.InvalidConfirmationTokenException;
import br.com.sergio.sapiensia.customer.PasswordCompromisedException;
import br.com.sergio.sapiensia.customer.WrongPasswordLimitReachedException;
import br.com.sergio.sapiensia.payment.CpfInvalidException;
import br.com.sergio.sapiensia.payment.CpfRequiredException;
import br.com.sergio.sapiensia.payment.CustomerHasPendingOrderException;
import br.com.sergio.sapiensia.payment.InvalidWebhookBodyException;
import br.com.sergio.sapiensia.payment.OrderInProgressException;
import br.com.sergio.sapiensia.payment.OrderNotFoundException;
import br.com.sergio.sapiensia.payment.PaymentProviderUnavailableException;
import br.com.sergio.sapiensia.payment.ProductAlreadyOwnedException;
import br.com.sergio.sapiensia.product.DownloadLimitReachedException;
import br.com.sergio.sapiensia.product.ProductFileRequiredException;
import br.com.sergio.sapiensia.product.ProductNotFoundException;
import br.com.sergio.sapiensia.product.ProductVersionConflictException;
import java.util.Map;
import java.util.TreeMap;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Turns validation and business errors into {@code ProblemDetail} with an {@code errors} map of field to code. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

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

    @ExceptionHandler(InvalidReauthenticationException.class)
    ResponseEntity<ProblemDetail> handleInvalidReauthentication() {
        return ResponseEntity.badRequest().body(problem(Map.of("credentials", "CREDENTIALS_INVALID")));
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

    /** Orders (#24): the answer never says whether an Order of another Customer exists. */
    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<Void> handleOrderNotFound() {
        return ResponseEntity.notFound().build();
    }

    /** Anonymization (#41): unknown or already anonymized e-mail; the title and code never carry the e-mail. */
    @ExceptionHandler(CustomerNotFoundException.class)
    ResponseEntity<ProblemDetail> handleCustomerNotFound() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Customer not found");
        problem.setProperty("code", "CUSTOMER_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    /** Anonymization (#41): a live charge exists; nothing is changed. */
    @ExceptionHandler(CustomerHasPendingOrderException.class)
    ResponseEntity<ProblemDetail> handleCustomerHasPendingOrder() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Customer has a pending order");
        problem.setProperty("code", "CUSTOMER_HAS_PENDING_ORDER");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(CpfRequiredException.class)
    ResponseEntity<ProblemDetail> handleCpfRequired() {
        return ResponseEntity.badRequest().body(problem(Map.of("cpf", "CPF_REQUIRED")));
    }

    @ExceptionHandler(CpfInvalidException.class)
    ResponseEntity<ProblemDetail> handleCpfInvalid() {
        return ResponseEntity.badRequest().body(problem(Map.of("cpf", "CPF_INVALID")));
    }

    @ExceptionHandler(OrderInProgressException.class)
    ResponseEntity<ProblemDetail> handleOrderInProgress() {
        return refusal(HttpStatus.CONFLICT, Map.of("order", "ORDER_IN_PROGRESS"));
    }

    @ExceptionHandler(ProductAlreadyOwnedException.class)
    ResponseEntity<ProblemDetail> handleProductAlreadyOwned() {
        return refusal(HttpStatus.CONFLICT, Map.of("order", "ALREADY_OWNED"));
    }

    @ExceptionHandler(PaymentProviderUnavailableException.class)
    ResponseEntity<ProblemDetail> handlePaymentProviderUnavailable() {
        return refusal(HttpStatus.BAD_GATEWAY, Map.of("payment", "PAYMENT_PROVIDER_UNAVAILABLE"));
    }

    @ExceptionHandler(DownloadLimitReachedException.class)
    ResponseEntity<ProblemDetail> handleDownloadLimitReached() {
        return refusal(HttpStatus.TOO_MANY_REQUESTS, Map.of("download", "DOWNLOAD_LIMIT"));
    }

    /** Request limits (SEC-014): no detail, only the code and the seconds to wait. */
    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<ProblemDetail> handleRateLimited(RateLimitedException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setTitle("Too many requests");
        problem.setProperty("code", "RATE_LIMITED");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(
                        HttpHeaders.RETRY_AFTER,
                        String.valueOf(Math.max(1, e.retryAfter().toSeconds())))
                .body(problem);
    }

    // Answers of the framework itself (SEC-018, SEC-020): the title only, never the text Spring would put in "detail".

    /** ResponseStatusException and friends: the status and its reason phrase, never the developer's reason. */
    @Override
    protected ResponseEntity<Object> handleErrorResponseException(
            ErrorResponseException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpStatus known = HttpStatus.resolve(status.value());
        HttpHeaders merged = new HttpHeaders();
        merged.putAll(ex.getHeaders());
        merged.putAll(headers);
        return titleOnly(
                known != null ? known : HttpStatus.INTERNAL_SERVER_ERROR,
                known != null ? known.getReasonPhrase() : "Error",
                merged);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return titleOnly(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return titleOnly(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", headers);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return invalidRequest();
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return invalidRequest();
    }

    /** A constraint on a request parameter, such as {@code page} above 10000 (SEC-015). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return invalidRequest();
    }

    /**
     * The last net: an exception nobody handled is a generic 500 and goes to the server log with its class and stack,
     * never with the request body. Security exceptions go on to the filter chain (401 or 403), and an exception that
     * already carries its own status keeps it.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> handleUnexpected(Exception e) throws Exception {
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
            throw e;
        }
        if (e instanceof ErrorResponse response) {
            // status and title only: a developer-written detail is not for the visitor (SEC-018)
            HttpStatusCode status = response.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            ProblemDetail problem = ProblemDetail.forStatus(status);
            problem.setTitle(
                    known != null ? known.getReasonPhrase() : response.getBody().getTitle());
            return ResponseEntity.status(status).headers(response.getHeaders()).body(problem);
        }
        if (RequestBodyLimitFilter.exceeded()) {
            return UnreadableBodyHandler.payloadTooLarge();
        }
        if (isClientAbort(e)) {
            // the client left; there is nobody to answer and nothing wrong on the server
            LOG.debug("Client disconnected: {}", e.getClass().getName());
            return null;
        }
        logUnexpected(e);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private static boolean isClientAbort(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ClientAbortException || t instanceof AsyncRequestNotUsableException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * Logs the class chain and the stack frames of the root cause, never {@code getMessage()} or {@code toString()}
     * (a message may carry personal data, e.g. {@code Key (email)=(...)}); the frames are enough to find the code.
     */
    private static void logUnexpected(Throwable e) {
        StringBuilder chain = new StringBuilder();
        Throwable root = e;
        for (int depth = 0; root != null && depth < 20; depth++) {
            if (depth > 0) {
                chain.append(" <- ");
            }
            chain.append(root.getClass().getName());
            if (root.getCause() == null || root.getCause() == root) {
                break;
            }
            root = root.getCause();
        }
        StringBuilder frames = new StringBuilder();
        for (StackTraceElement frame : root.getStackTrace()) {
            frames.append("\n\tat ").append(frame);
        }
        LOG.error("Unhandled exception {}{}", chain, frames);
    }

    private static ResponseEntity<Object> invalidRequest() {
        return titleOnly(HttpStatus.BAD_REQUEST, "Invalid request", HttpHeaders.EMPTY);
    }

    private static ResponseEntity<Object> titleOnly(HttpStatus status, String title, HttpHeaders headers) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    private static ResponseEntity<ProblemDetail> refusal(HttpStatus status, Map<String, String> errors) {
        ProblemDetail problem = problem(errors);
        problem.setStatus(status);
        return ResponseEntity.status(status).body(problem);
    }

    private static ProblemDetail problem(Map<String, String> errors) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return problem;
    }
}
