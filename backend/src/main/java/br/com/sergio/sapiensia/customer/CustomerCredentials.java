package br.com.sergio.sapiensia.customer;

import java.time.Instant;
import java.util.UUID;

/**
 * What the login needs to know about an account: read by a projection, so the entity stays untouched. The session
 * version goes into the session, so a password change (which increments it) ends every older session.
 */
record CustomerCredentials(UUID id, String email, String passwordHash, Instant emailConfirmedAt, int sessionVersion) {

    // the generated toString would print the e-mail and the password hash
    @Override
    public String toString() {
        return "CustomerCredentials[id=" + id + "]";
    }
}
