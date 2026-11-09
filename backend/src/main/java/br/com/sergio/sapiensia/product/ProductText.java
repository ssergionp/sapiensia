package br.com.sergio.sapiensia.product;

import java.text.Normalizer;

/**
 * Text cleaning shared by the Product bodies: NFC, then no spaces at the ends. A text with no visible character (only
 * spaces and invisible format characters such as U+200B) becomes empty, so it is reported as required, not as invalid.
 */
final class ProductText {

    private ProductText() {}

    static String clean(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = Normalizer.normalize(text, Normalizer.Form.NFC).strip();
        return cleaned.codePoints().allMatch(ProductText::isInvisible) ? "" : cleaned;
    }

    private static boolean isInvisible(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }
}
