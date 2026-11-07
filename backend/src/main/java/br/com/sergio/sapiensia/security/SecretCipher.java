package br.com.sergio.sapiensia.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts small secrets with AES-256-GCM. Stored format: "v1:" + Base64(nonce 12 || cipher text || tag 16).
 * The AAD binds a value to its owner, so a value copied to another row fails to decrypt.
 */
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final String DECRYPT_FAILURE = "MFA secret could not be decrypted";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(MfaProperties properties) {
        this.key = new SecretKeySpec(decodeKey(properties.encryptionKey()), "AES");
    }

    private static byte[] decodeKey(String encoded) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded == null ? "" : encoded.trim());
        } catch (IllegalArgumentException e) {
            // no cause: its message could quote the value
            throw new IllegalStateException("Invalid app.mfa.encryption-key");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("Invalid app.mfa.encryption-key");
        }
        return bytes;
    }

    public String encrypt(byte[] plain, String aad) {
        requireAad(aad);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain);
            byte[] out = ByteBuffer.allocate(NONCE_BYTES + encrypted.length)
                    .put(nonce)
                    .put(encrypted)
                    .array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MFA secret could not be encrypted");
        }
    }

    private static void requireAad(String aad) {
        if (aad == null) {
            throw new IllegalArgumentException("aad is required");
        }
    }

    public byte[] decrypt(String stored, String aad) {
        requireAad(aad);
        try {
            if (stored == null || !stored.startsWith(PREFIX)) {
                throw new IllegalStateException(DECRYPT_FAILURE);
            }
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (raw.length < NONCE_BYTES + TAG_BYTES) {
                throw new IllegalStateException(DECRYPT_FAILURE);
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(raw, NONCE_BYTES, raw.length - NONCE_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // no cause: nothing about the value or the key may leak
            throw new IllegalStateException(DECRYPT_FAILURE);
        }
    }
}
