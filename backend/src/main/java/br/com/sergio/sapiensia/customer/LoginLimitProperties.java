package br.com.sergio.sapiensia.customer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Login attempt limits (app.login-limit.*): per-account lockout and per-IP rate limit. */
@Validated
@ConfigurationProperties("app.login-limit")
record LoginLimitProperties(
        @Positive int accountMaxFailures,
        @NotNull Duration accountWindow,
        @NotNull Duration accountLock,
        @Positive int ipMaxAttempts,
        @NotNull Duration ipWindow,
        String clientIpHeader) {}
