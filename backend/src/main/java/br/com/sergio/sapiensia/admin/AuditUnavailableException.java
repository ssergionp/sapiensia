package br.com.sergio.sapiensia.admin;

/**
 * The SUCCESS audit event of a password step could not be written, so the action did not happen (no trail, no
 * action); the controller answers 503. No message: nothing about the request.
 */
class AuditUnavailableException extends RuntimeException {

    AuditUnavailableException() {
        super(null, null, false, false);
    }
}
