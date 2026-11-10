package br.com.sergio.sapiensia.product;

/** The uploaded file was refused; the API answers with the reason as the error code. No message, no file data. */
public class ProductFileRejectedException extends RuntimeException {

    private final ProductFileRejection reason;

    ProductFileRejectedException(ProductFileRejection reason) {
        super(null, null, false, false);
        this.reason = reason;
    }

    public ProductFileRejection reason() {
        return reason;
    }
}
