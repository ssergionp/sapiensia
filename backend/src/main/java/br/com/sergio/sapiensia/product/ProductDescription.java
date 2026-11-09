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

/** The rules of a Product description (SEC-013), shared by the create and edit bodies. Tab and line breaks are allowed. */
@NotBlank(message = "DESCRIPTION_REQUIRED")
@Size(max = 5000, message = "DESCRIPTION_TOO_LONG")
@Pattern(regexp = "[[^\\p{Cc}\\p{Cs}][\\t\\n\\r]]*", message = "DESCRIPTION_INVALID")
@Constraint(validatedBy = {})
@Documented
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@interface ProductDescription {

    String message() default "DESCRIPTION_INVALID";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
