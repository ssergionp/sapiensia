package br.com.sergio.sapiensia.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Request limits per operation (app.rate-limit.&lt;operation-in-kebab-case&gt;.*) and the IP capacity floor.
 *
 * <p>An operation with no limit per account simply omits {@code account-capacity} and {@code account-window}
 * (both null); setting only one of them stops the application. {@code ip-capacity-floor} raises every per-IP capacity
 * to at least that value (0 = no effect); it never changes a per-account limit.
 */
@Validated
@ConfigurationProperties("app.rate-limit")
record RateLimitProperties(
        @PositiveOrZero int ipCapacityFloor,
        @NotNull @Valid Limit orderCreate,
        @NotNull @Valid Limit orderRead,
        @NotNull @Valid Limit download,
        @NotNull @Valid Limit cover,
        @NotNull @Valid Limit signUp,
        @NotNull @Valid Limit passwordResetRequest,
        @NotNull @Valid Limit passwordResetConfirm,
        @NotNull @Valid Limit emailConfirmation,
        @NotNull @Valid Limit adminPasswordSetup) {

    /** Fixed window: {@code capacity} requests per {@code window}; the account part is optional (null = none). */
    record Limit(
            @Positive Integer accountCapacity,
            Duration accountWindow,
            @Positive int ipCapacity,
            @NotNull Duration ipWindow) {

        @AssertTrue(message = "account-capacity and account-window go together")
        boolean isAccountPairConsistent() {
            return (accountCapacity == null) == (accountWindow == null);
        }

        @AssertTrue(message = "windows must be greater than zero")
        boolean isWindowsPositive() {
            return positive(ipWindow) && positive(accountWindow);
        }

        private static boolean positive(Duration window) {
            return window == null || (!window.isNegative() && !window.isZero());
        }
    }

    Limit limit(RateLimitedOperation operation) {
        return switch (operation) {
            case ORDER_CREATE -> orderCreate;
            case ORDER_READ -> orderRead;
            case DOWNLOAD -> download;
            case COVER -> cover;
            case SIGN_UP -> signUp;
            case PASSWORD_RESET_REQUEST -> passwordResetRequest;
            case PASSWORD_RESET_CONFIRM -> passwordResetConfirm;
            case EMAIL_CONFIRMATION -> emailConfirmation;
            case ADMIN_PASSWORD_SETUP -> adminPasswordSetup;
        };
    }
}
