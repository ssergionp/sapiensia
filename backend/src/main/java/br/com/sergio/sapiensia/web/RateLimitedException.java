package br.com.sergio.sapiensia.web;

import java.time.Duration;

/** A request limit was reached (SEC-014): answered with 429, RATE_LIMITED and Retry-After. */
public class RateLimitedException extends RuntimeException {

    private final transient Duration retryAfter;

    RateLimitedException(Duration retryAfter) {
        super("Rate limited", null, false, false);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
