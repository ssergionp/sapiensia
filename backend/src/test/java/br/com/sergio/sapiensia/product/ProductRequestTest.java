package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductRequestTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static ProductRequest request(String title, String description, ProductCategory category, Integer price) {
        return new ProductRequest(title, description, category, price);
    }

    private static ProductRequest valid() {
        return request("Guia de Pix", "Passo a passo.", ProductCategory.FINANCE, 4990);
    }

    private static Set<String> codes(ProductRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("FR-016: a complete request is valid")
    void shouldAcceptACompleteRequest() {
        assertThat(codes(valid())).isEmpty();
    }

    @Test
    @DisplayName("SEC-013: title of 120 characters is valid and 121 is too long")
    void shouldLimitTheTitleTo120() {
        assertThat(codes(request("a".repeat(120), "d", ProductCategory.LAW, 500)))
                .isEmpty();
        assertThat(codes(request("a".repeat(121), "d", ProductCategory.LAW, 500)))
                .containsExactly("TITLE_TOO_LONG");
    }

    @Test
    @DisplayName("SEC-013: description of 5000 characters is valid and 5001 is too long")
    void shouldLimitTheDescriptionTo5000() {
        assertThat(codes(request("t", "a".repeat(5000), ProductCategory.LAW, 500)))
                .isEmpty();
        assertThat(codes(request("t", "a".repeat(5001), ProductCategory.LAW, 500)))
                .containsExactly("DESCRIPTION_TOO_LONG");
    }

    @Test
    @DisplayName("SEC-013: blank or missing text is required")
    void shouldRequireTitleAndDescription() {
        assertThat(codes(request("   ", "\n \t", ProductCategory.LAW, 500)))
                .containsExactlyInAnyOrder("TITLE_REQUIRED", "DESCRIPTION_REQUIRED");
        assertThat(codes(request(null, null, null, null)))
                .containsExactlyInAnyOrder(
                        "TITLE_REQUIRED", "DESCRIPTION_REQUIRED", "CATEGORY_REQUIRED", "PRICE_REQUIRED");
    }

    @Test
    @DisplayName("SEC-013: NUL, control characters and a lone surrogate are invalid, never a 500 from PostgreSQL")
    void shouldRejectControlCharactersAndLoneSurrogates() {
        assertThat(codes(request("a\u0000b", "d", ProductCategory.LAW, 500))).containsExactly("TITLE_INVALID");
        assertThat(codes(request("a\tb", "d", ProductCategory.LAW, 500))).containsExactly("TITLE_INVALID");
        assertThat(codes(request("a\ud800b", "d", ProductCategory.LAW, 500))).containsExactly("TITLE_INVALID");
        assertThat(codes(request("t", "a\u0000b", ProductCategory.LAW, 500))).containsExactly("DESCRIPTION_INVALID");
        assertThat(codes(request("t", "a\u001bb", ProductCategory.LAW, 500))).containsExactly("DESCRIPTION_INVALID");
        assertThat(codes(request("t", "a\udc00b", ProductCategory.LAW, 500))).containsExactly("DESCRIPTION_INVALID");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\u202A", "\u202B", "\u202C", "\u202D", "\u202E", "\u2066", "\u2067", "\u2068", "\u2069", "\u200B",
                "\u2060", "\uFEFF", "\u200E", "\u200F", "\u061C", "\u00AD", "\u2061", "\u2062", "\u2063", "\u2064",
                "\u206A", "\u206F", "\uFFF9", "\uFFFA", "\uFFFB", "\uDB40\uDC00", "\uDB40\uDC7F"
            })
    @DisplayName("SEC-013: bidi controls and invisible characters inside the text are invalid")
    void shouldRejectBidiAndInvisibleCharacters(String forbidden) {
        assertThat(codes(request("a" + forbidden + "b", "d", ProductCategory.LAW, 500)))
                .containsExactly("TITLE_INVALID");
        assertThat(codes(request("t", "a" + forbidden + "b", ProductCategory.LAW, 500)))
                .containsExactly("DESCRIPTION_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u200B", " \u200B ", "\u202E", "\uFEFF\u2060", " ", "‎ \u200B", "　"})
    @DisplayName("SEC-013: text with no visible character is required")
    void shouldRequireAVisibleCharacter(String invisible) {
        assertThat(codes(request(invisible, invisible, ProductCategory.LAW, 500)))
                .containsExactlyInAnyOrder("TITLE_REQUIRED", "DESCRIPTION_REQUIRED");
    }

    @Test
    @DisplayName("SEC-013: a zero width joiner in an emoji sequence is accepted")
    void shouldAcceptAZeroWidthJoiner() {
        String family = "👨‍👩‍👧";
        assertThat(codes(request("Guia " + family, "d " + family, ProductCategory.LAW, 500)))
                .isEmpty();
    }

    @Test
    @DisplayName("SEC-013: a valid surrogate pair (emoji) is accepted")
    void shouldAcceptASurrogatePair() {
        assertThat(codes(request("Guia 😀", "d 😀", ProductCategory.LAW, 500))).isEmpty();
    }

    @Test
    @DisplayName("SEC-013: line breaks and tabs are allowed only in the description")
    void shouldAllowWhitespaceControlsOnlyInTheDescription() {
        assertThat(codes(request("t", "linha 1\r\nlinha\t2", ProductCategory.LAW, 500)))
                .isEmpty();
        assertThat(codes(request("a\nb", "d", ProductCategory.LAW, 500))).containsExactly("TITLE_INVALID");
    }

    @Test
    @DisplayName("SEC-013: Unicode space separators (NBSP, ideographic space) are stripped from both ends")
    void shouldStripUnicodeSpaceSeparators() {
        ProductRequest padded =
                request("\u00A0\u3000Guia\u2007\u00A0", "\u00A0 texto \u202F\u00A0", ProductCategory.LAW, 500);

        assertThat(padded.title()).isEqualTo("Guia");
        assertThat(padded.description()).isEqualTo("texto");
    }

    @Test
    @DisplayName("SEC-013: text is normalized to NFC and stripped before it is validated")
    void shouldNormalizeToNfcAndStrip() {
        ProductRequest decomposed = request("  Café  ", "\n é \n", ProductCategory.LAW, 500);

        assertThat(decomposed.title()).isEqualTo("Café");
        assertThat(decomposed.description()).isEqualTo("é");
        assertThat(codes(decomposed)).isEmpty();
        // a title whose spaces are stripped away counts the stripped length
        assertThat(codes(request(" ".repeat(10) + "a".repeat(120) + " ".repeat(10), "d", ProductCategory.LAW, 500)))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 4990, 999_999})
    @DisplayName("FR-018: prices from 500 to 999999 cents are valid")
    void shouldAcceptPricesInTheRange(int price) {
        assertThat(codes(request("t", "d", ProductCategory.LAW, price))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, 499})
    @DisplayName("FR-018: prices below 500 cents are too low")
    void shouldRejectPricesBelowTheMinimum(int price) {
        assertThat(codes(request("t", "d", ProductCategory.LAW, price))).containsExactly("PRICE_TOO_LOW");
    }

    @ParameterizedTest
    @ValueSource(ints = {1_000_000, Integer.MAX_VALUE})
    @DisplayName("FR-018: prices above 999999 cents are too high")
    void shouldRejectPricesAboveTheMaximum(int price) {
        assertThat(codes(request("t", "d", ProductCategory.LAW, price))).containsExactly("PRICE_TOO_HIGH");
    }
}
