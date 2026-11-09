package br.com.sergio.sapiensia.admin;

import br.com.sergio.sapiensia.security.Tokens;
import br.com.sergio.sapiensia.security.Totp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The one-time recovery codes of the Administrator (ADR-0004): 10 codes of 15 random bytes (120 bits) in Base32, so a
 * plain SHA-256 is enough to store them (ASVS 5.0.0 6.5.2: 112 bits or more). Only the hash is ever persisted.
 */
final class RecoveryCodes {

    static final int COUNT = 10;
    private static final int CODE_BYTES = 15;
    private static final int CODE_LENGTH = 24;
    // a typed code with every separator is 29 characters; anything much longer is refused before any work
    private static final int MAX_INPUT_LENGTH = 64;
    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]");
    private static final Pattern FORMAT = Pattern.compile("[A-Z2-7]{" + CODE_LENGTH + "}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A stored code not used yet; {@code toString()} hides the hash. */
    record Unused(UUID id, String codeHash) {

        @Override
        public String toString() {
            return "Unused[id=" + id + "]";
        }
    }

    private RecoveryCodes() {}

    /** 10 new codes, each 24 characters A-Z2-7. */
    static List<String> generate() {
        List<String> codes = new ArrayList<>(COUNT);
        while (codes.size() < COUNT) {
            byte[] bytes = new byte[CODE_BYTES];
            RANDOM.nextBytes(bytes);
            String code = Totp.toBase32(bytes);
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** The code as stored: hyphens and spaces removed, upper case; empty when it cannot be a code. */
    static Optional<String> normalize(String typed) {
        if (typed == null || typed.length() > MAX_INPUT_LENGTH) {
            return Optional.empty();
        }
        // before toUpperCase, which folds some non-ASCII letters (dotless i, long s, sharp s) into A-Z
        if (!typed.chars().allMatch(c -> c < 0x80)) {
            return Optional.empty();
        }
        String code = SEPARATORS.matcher(typed).replaceAll("").toUpperCase(Locale.ROOT);
        return FORMAT.matcher(code).matches() ? Optional.of(code) : Optional.empty();
    }

    /** The persisted form of a normalized code. */
    static String hash(String normalized) {
        return Tokens.hash(normalized);
    }

    /**
     * The id of the unused code that matches {@code normalized}. Every hash is compared in constant time and the loop
     * never stops at the first match, so the time does not tell which position (if any) matched.
     */
    static Optional<UUID> matching(List<Unused> unused, String normalized) {
        byte[] typed = hash(normalized).getBytes(StandardCharsets.US_ASCII);
        UUID found = null;
        for (Unused candidate : unused) {
            boolean equal = MessageDigest.isEqual(typed, candidate.codeHash().getBytes(StandardCharsets.US_ASCII));
            if (equal && found == null) {
                found = candidate.id();
            }
        }
        return Optional.ofNullable(found);
    }
}
