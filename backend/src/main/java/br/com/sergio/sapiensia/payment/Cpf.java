package br.com.sergio.sapiensia.payment;

import java.util.Optional;

/** A valid CPF. It lives only in memory for one request: it is never stored, logged or printed (SEC-033). */
public final class Cpf {

    private final String digits;

    private Cpf(String digits) {
        this.digits = digits;
    }

    /** Strips dots, dashes and whitespace, then checks 11 digits, not all equal, and both check digits. */
    public static Optional<Cpf> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("[.\\-\\s]", "");
        if (digits.length() != 11 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return Optional.empty();
        }
        if (digits.chars().distinct().count() == 1) {
            return Optional.empty();
        }
        if (checkDigit(digits, 9) != digits.charAt(9) - '0' || checkDigit(digits, 10) != digits.charAt(10) - '0') {
            return Optional.empty();
        }
        return Optional.of(new Cpf(digits));
    }

    private static int checkDigit(String digits, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (digits.charAt(i) - '0') * (length + 1 - i);
        }
        int rest = (sum * 10) % 11;
        return rest == 10 ? 0 : rest;
    }

    public String digits() {
        return digits;
    }

    @Override
    public String toString() {
        return "Cpf[redacted]";
    }
}
