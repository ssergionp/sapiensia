package br.com.sergio.sapiensia.product;

/** Result of validating an uploaded PDF. */
public sealed interface PdfCheck {

    record Valid(int pageCount) implements PdfCheck {}

    record Rejected(ProductFileRejection reason) implements PdfCheck {}
}
