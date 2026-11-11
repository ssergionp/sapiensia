package br.com.sergio.sapiensia.product;

import java.text.BreakIterator;
import java.util.Locale;

/** The short text of a Product in the Catalog list (FR-016). */
final class CatalogSummary {

    static final int MAX = 200;

    private static final String ELLIPSIS = "\u2026";

    private CatalogSummary() {}

    /**
     * Collapses spaces and line breaks into one space; a longer text is cut at the last space within {@link #MAX}
     * characters and gets an ellipsis. The cut always falls on a grapheme boundary, so a surrogate pair, a combining
     * accent or a ZWJ emoji is never split. Without any space in the range (one huge "word"), the cut is the last
     * grapheme boundary: there is no other way to fit.
     */
    static String of(String description) {
        String text = description.strip().replaceAll("\\s+", " ");
        if (text.length() <= MAX) {
            return text;
        }
        BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
        graphemes.setText(text);
        // the last boundary strictly before MAX + 1, that is, at most MAX
        int cut = graphemes.preceding(MAX + 1);
        if (text.charAt(cut) != ' ') {
            int space = text.lastIndexOf(' ', cut - 1);
            if (space > 0) {
                cut = space;
            }
        }
        return text.substring(0, cut).stripTrailing() + ELLIPSIS;
    }
}
