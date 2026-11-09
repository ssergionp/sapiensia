package br.com.sergio.sapiensia.product;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The rules of a Product title (SEC-013), shared by the create and edit bodies. Not {@code @ReportAsSingleViolation}:
 * each rule reports its own error code. The pattern is a single character class (no stack-deep {@code (a|b)*});
 * {@code \p{Cc}} is the control characters (NUL included) and {@code \p{Cs}} a lone surrogate, which PostgreSQL
 * refuses.
 */
@NotBlank(message = "TITLE_REQUIRED")
@Size(max = 120, message = "TITLE_TOO_LONG")
@Pattern(regexp = "[^" + ProductText.FORBIDDEN + "]*", message = "TITLE_INVALID")
@Constraint(validatedBy = {})
@Documented
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@interface ProductTitle {

    String message() default "TITLE_INVALID";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
