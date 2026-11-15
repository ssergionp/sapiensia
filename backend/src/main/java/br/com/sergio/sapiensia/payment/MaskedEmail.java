package br.com.sergio.sapiensia.payment;

/** The only way to show a Customer's e-mail in the panel: first letter, three asterisks and the domain (FR-020). */
final class MaskedEmail {

    private MaskedEmail() {}

    /** Always three asterisks, so the length of the address is not revealed; without a local part, only them. */
    static String of(String email) {
        if (email == null) {
            return "***";
        }
        int at = email.indexOf('@');
        if (at < 1) {
            return "***";
        }
        return email.substring(0, email.offsetByCodePoints(0, 1)) + "***" + email.substring(at);
    }
}
