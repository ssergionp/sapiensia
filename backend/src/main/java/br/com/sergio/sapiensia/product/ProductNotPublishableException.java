package br.com.sergio.sapiensia.product;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishing was asked for a Product that lacks its file or its Capa (FR-011); the API answers 409 with one code per
 * missing item, keyed {@code file} and {@code cover}. No message.
 */
public class ProductNotPublishableException extends RuntimeException {

    private final transient Map<String, String> errors;

    ProductNotPublishableException(Map<String, String> errors) {
        super(null, null, false, false);
        this.errors = new LinkedHashMap<>(errors);
    }

    public Map<String, String> errors() {
        return errors;
    }
}
