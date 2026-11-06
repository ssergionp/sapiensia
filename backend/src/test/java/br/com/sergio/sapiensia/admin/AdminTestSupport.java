package br.com.sergio.sapiensia.admin;

import java.time.Duration;
import java.time.Instant;

/** Small polling helper for assertions on work done by background tasks. */
final class AdminTestSupport {

    private AdminTestSupport() {}

    interface Check {
        boolean ok() throws Exception;
    }

    /** Polls every 50 ms until the check passes; fails with the message when the timeout is reached. */
    static void eventually(Duration timeout, String what, Check check) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (!check.ok()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.sleep(50);
        }
    }
}
