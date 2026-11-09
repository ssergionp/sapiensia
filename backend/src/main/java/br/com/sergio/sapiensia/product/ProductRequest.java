package br.com.sergio.sapiensia.product;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.text.Normalizer;

/**
 * The body of a new Product (FR-016, FR-018). Text is normalized to NFC and stripped before it is validated and stored.
 * The message of each constraint is the error code the screen turns into text. Extra JSON fields (id, status, version)
 * are not part of the record, so they are ignored (SEC-012). The price is in cents, within the commercial range of
 * ADR-0007; the database only keeps {@code price_cents > 0}.
 *
 * <p>The patterns are single character classes: Java's {@code (a|b)*} recurses and could overflow the stack on a long
 * text. {@code \p{Cc}} is the control characters (NUL included) and {@code \p{Cs}} a lone surrogate; PostgreSQL
 * refuses both.
 */
record ProductRequest(
        @NotBlank(message = "TITLE_REQUIRED")
        @Size(max = 120, message = "TITLE_TOO_LONG")
        @Pattern(regexp = "[^\\p{Cc}\\p{Cs}]*", message = "TITLE_INVALID")
        String title,

        @NotBlank(message = "DESCRIPTION_REQUIRED")
        @Size(max = 5000, message = "DESCRIPTION_TOO_LONG")
        @Pattern(regexp = "[[^\\p{Cc}\\p{Cs}][\\t\\n\\r]]*", message = "DESCRIPTION_INVALID")
        String description,

        @NotNull(message = "CATEGORY_REQUIRED") ProductCategory category,

        @NotNull(message = "PRICE_REQUIRED")
        @Min(value = 500, message = "PRICE_TOO_LOW")
        @Max(value = 999_999, message = "PRICE_TOO_HIGH")
        Integer priceCents) {

    ProductRequest {
        title = clean(title);
        description = clean(description);
    }

    private static String clean(String text) {
        return text == null
                ? null
                : Normalizer.normalize(text, Normalizer.Form.NFC).strip();
    }
}
