package br.com.sergio.sapiensia.product;

import java.text.Normalizer;

/** Text cleaning shared by the Product bodies: NFC, then no spaces at the ends. */
final class ProductText {

    private ProductText() {}

    static String clean(String text) {
        return text == null
                ? null
                : Normalizer.normalize(text, Normalizer.Form.NFC).strip();
    }
}
