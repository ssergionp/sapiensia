package br.com.sergio.sapiensia.product;

/** The Customer already took the daily limit of download links for this Product (SEC-015); carries no Customer data. */
public class DownloadLimitReachedException extends RuntimeException {

    public DownloadLimitReachedException() {
        super("Daily download limit reached");
    }
}
