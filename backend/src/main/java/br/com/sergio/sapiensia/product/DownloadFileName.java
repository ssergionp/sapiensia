package br.com.sergio.sapiensia.product;

import java.text.Normalizer;
import java.util.Locale;

/** The name the browser saves a downloaded PDF under: derived from the Product title, never from an upload. */
final class DownloadFileName {

    private static final int MAX_LENGTH = 60;
    private static final String FALLBACK = "arquivo.pdf";

    private DownloadFileName() {}

    /** Lower case, no accents, only {@code [a-z0-9-]}, at most 60 characters, plus {@code .pdf}. */
    static String of(String productTitle) {
        String text = productTitle == null ? "" : productTitle;
        String slug = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? FALLBACK : slug + ".pdf";
    }
}
