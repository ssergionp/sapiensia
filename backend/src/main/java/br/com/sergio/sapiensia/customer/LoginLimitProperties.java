package br.com.sergio.sapiensia.customer;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Login attempt limits (app.login-limit.*): per-account lockout and per-IP rate limit. */
@ConfigurationProperties("app.login-limit")
record LoginLimitProperties(
        int accountMaxFailures,
        Duration accountWindow,
        Duration accountLock,
        int ipMaxAttempts,
        Duration ipWindow,
        String clientIpHeader) {}
