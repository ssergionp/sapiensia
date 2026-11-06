package br.com.sergio.sapiensia.security;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.function.LongFunction;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.util.encoders.Base32;

/** TOTP calculation per RFC 6238 (HMAC-SHA1, 6 digits, 30 s step). Pure and stateless. */
public final class Totp {

    static final int DIGITS = 6;
    static final int STEP_SECONDS = 30;
    private static final int SECRET_BYTES = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** Upper-case Base32 without padding. */
    public static String toBase32(byte[] secret) {
        return Base32.toBase32String(secret).replace("=", "");
    }

    public static long stepAt(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), STEP_SECONDS);
    }

    public static String code(byte[] secret, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash =
                    mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            long modulus = 1;
            for (int i = 0; i < digits; i++) {
                modulus *= 10;
            }
            return String.format("%0" + digits + "d", binary % modulus);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is not available", e);
        }
    }

    /** Returns the step (now-1, now or now+1) whose code equals {@code code}, if any. */
    public static OptionalLong matchingStep(byte[] secret, String code, Instant now) {
        return matchingStep(code, stepAt(now), step -> code(secret, step, DIGITS));
    }

    // Package-private seam so tests can observe that every candidate is computed.
    static OptionalLong matchingStep(String code, long currentStep, LongFunction<String> codeAt) {
        if (!isWellFormed(code)) {
            return OptionalLong.empty();
        }
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long found = -1;
        boolean matched = false;
        // No early exit: all three candidates are always computed and compared.
        for (long step = currentStep - 1; step <= currentStep + 1; step++) {
            byte[] expected = codeAt.apply(step).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(given, expected) && !matched) {
                matched = true;
                found = step;
            }
        }
        return matched ? OptionalLong.of(found) : OptionalLong.empty();
    }

    private static boolean isWellFormed(String code) {
        if (code == null || code.length() != DIGITS) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    public static String otpauthUri(String issuer, String account, byte[] secret) {
        String encodedIssuer = encode(issuer);
        return "otpauth://totp/"
                + encodedIssuer
                + ":"
                + encode(account)
                + "?secret="
                + toBase32(secret)
                + "&issuer="
                + encodedIssuer
                + "&algorithm=SHA1&digits="
                + DIGITS
                + "&period="
                + STEP_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
