package br.com.sergio.sapiensia.web;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How often a refusal warning may be written per key (app.refusal-log.interval). Zero never holds a line (used by the
 * tests, which share one Spring context and check log lines); a negative value stops the application.
 */
@Validated
@ConfigurationProperties("app.refusal-log")
record RefusalLogProperties(@NotNull Duration interval) {

    @AssertTrue(message = "interval must not be negative")
    boolean isIntervalNotNegative() {
        return interval == null || !interval.isNegative();
    }
}
