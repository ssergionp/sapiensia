package br.com.sergio.sapiensia.product;

import java.util.UUID;
import java.util.regex.Pattern;

/** Object keys of the product files. Only the server builds them: the uploaded file name never enters a key. */
final class ProductFileKeys {

    private static final Pattern PATTERN = Pattern.compile("^products/[0-9a-f-]{36}/[0-9a-f-]{36}\\.pdf$");

    private ProductFileKeys() {}

    static String newKey(UUID productId) {
        return "products/" + productId + "/" + UUID.randomUUID() + ".pdf";
    }

    /** Defense in depth against path traversal: both storages refuse any key outside the pattern. */
    static boolean isValid(String key) {
        return key != null && PATTERN.matcher(key).matches();
    }
}
