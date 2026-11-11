package br.com.sergio.sapiensia.product;

/**
 * Replacing the file of a Product that was ever published needs a recent authentication, and the request did not have
 * one when the service looked under the row lock. The API answers 403 REAUTHENTICATION_REQUIRED. No message.
 */
public class ProductFileReauthenticationRequiredException extends RuntimeException {

    ProductFileReauthenticationRequiredException() {
        super(null, null, false, false);
    }
}
