package br.com.sergio.sapiensia.product;

import java.text.Normalizer;

/**
 * Text cleaning shared by the Product bodies: NFC, then no spaces at the ends. A text with no visible character (only
 * spaces and invisible format characters such as U+200B) becomes empty, so it is reported as required, not as invalid.
 */
final class ProductText {

    /**
     * Regex class body (without brackets) of the characters no Product text may contain: controls, lone surrogates,
     * bidi controls and invisible format characters. U+200C and U+200D (joiners) stay allowed for emoji and scripts.
     */
    static final String FORBIDDEN = "\\p{Cc}\\p{Cs}\\u202A-\\u202E\\u2066-\\u2069\\u200B\\u2060\\uFEFF"
            + "\\u200E\\u200F\\u061C\\u00AD\\u2061-\\u2064\\u206A-\\u206F\\uFFF9-\\uFFFB\\x{E0000}-\\x{E007F}";

    private ProductText() {}

    static String clean(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = stripSpaces(Normalizer.normalize(text, Normalizer.Form.NFC));
        return cleaned.codePoints().allMatch(ProductText::isInvisible) ? "" : cleaned;
    }

    /** {@code String.strip()} leaves NBSP and other Unicode space separators; this removes them too. */
    private static String stripSpaces(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.codePointAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end);
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isInvisible(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }
}
