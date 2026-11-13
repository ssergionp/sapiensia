package br.com.sergio.sapiensia.product;

/** The uploaded cover was refused; the API answers with the reason as the error code. No message, no file data. */
public class ProductCoverRejectedException extends RuntimeException {

    private final ProductCoverRejection reason;

    ProductCoverRejectedException(ProductCoverRejection reason) {
        super(null, null, false, false);
        this.reason = reason;
    }

    public ProductCoverRejection reason() {
        return reason;
    }
}
