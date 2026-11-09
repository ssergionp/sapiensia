package br.com.sergio.sapiensia.product;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The commercial price range in cents (ADR-0007), shared by the create and price-change bodies. */
@NotNull(message = "PRICE_REQUIRED")
@Min(value = 500, message = "PRICE_TOO_LOW")
@Max(value = 999_999, message = "PRICE_TOO_HIGH")
@Constraint(validatedBy = {})
@Documented
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@interface ProductPrice {

    String message() default "PRICE_TOO_LOW";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
