package br.com.sergio.sapiensia.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    // fictitious key (32 zero bytes), tests only
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String AAD = "administrator-totp:11111111-1111-1111-1111-111111111111";
    private static final byte[] PLAIN = "JBSWY3DPEHPK3PXP".getBytes(StandardCharsets.UTF_8);

    private final SecretCipher cipher = new SecretCipher(new MfaProperties(KEY));

    @Test
    @DisplayName("SEC-003: round trip returns the original bytes")
    void shouldRoundTrip() {
        String stored = cipher.encrypt(PLAIN, AAD);

        assertThat(stored).startsWith("v1:");
        assertThat(cipher.decrypt(stored, AAD)).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("SEC-003: stored format is v1 + Base64(nonce 12 || cipher text || tag 16)")
    void shouldUseTheDocumentedLayout() {
        String stored = cipher.encrypt(PLAIN, AAD);

        byte[] raw = Base64.getDecoder().decode(stored.substring(3));
        assertThat(raw).hasSize(12 + PLAIN.length + 16);
    }

    @Test
    @DisplayName("SEC-003: two encryptions of the same value differ (fresh nonce)")
    void shouldUseAFreshNonceEachTime() {
        assertThat(cipher.encrypt(PLAIN, AAD)).isNotEqualTo(cipher.encrypt(PLAIN, AAD));
    }

    @Test
    @DisplayName("SEC-003: a different AAD fails to decrypt")
    void shouldRejectADifferentAad() {
        String stored = cipher.encrypt(PLAIN, AAD);

        assertThatThrownBy(() -> cipher.decrypt(stored, "administrator-totp:other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MFA secret could not be decrypted");
    }

    @Test
    @DisplayName("SEC-003: a tampered byte fails to decrypt")
    void shouldRejectATamperedValue() {
        String stored = cipher.encrypt(PLAIN, AAD);
        byte[] raw = Base64.getDecoder().decode(stored.substring(3));
        raw[raw.length / 2] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered, AAD))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MFA secret could not be decrypted");
    }

    @Test
    @DisplayName("SEC-003: another prefix, a too-short value, bad Base64 and null fail to decrypt")
    void shouldRejectMalformedValues() {
        String stored = cipher.encrypt(PLAIN, AAD);
        String otherPrefix = "v2:" + stored.substring(3);
        String tooShort = "v1:" + Base64.getEncoder().encodeToString(new byte[27]);

        for (String bad : new String[] {otherPrefix, tooShort, "v1:***", "", null}) {
            assertThatThrownBy(() -> cipher.decrypt(bad, AAD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("MFA secret could not be decrypted");
        }
    }

    @Test
    @DisplayName("SEC-003: exception messages never carry the cipher text, the AAD or the key")
    void shouldNotLeakInMessages() {
        String stored = cipher.encrypt(PLAIN, AAD);
        Throwable failure = catchThrowable(() -> cipher.decrypt(stored, "other-aad"));
        Throwable badKey = catchThrowable(() -> new SecretCipher(new MfaProperties(KEY + "x")));

        assertThat(failure.getMessage())
                .doesNotContain(stored)
                .doesNotContain(AAD)
                .doesNotContain(KEY);
        assertThat(failure.getCause()).isNull();
        assertThat(badKey).isInstanceOf(IllegalStateException.class).hasMessage("Invalid app.mfa.encryption-key");
    }

    @Test
    @DisplayName("SEC-003: a null AAD is refused explicitly, on both directions")
    void shouldRejectANullAad() {
        String stored = cipher.encrypt(PLAIN, AAD);

        assertThatThrownBy(() -> cipher.encrypt(PLAIN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("aad is required");
        assertThatThrownBy(() -> cipher.decrypt(stored, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("aad is required");
    }

    @Test
    @DisplayName("SEC-003: MfaProperties.toString redacts the key")
    void shouldRedactTheKeyInToString() {
        assertThat(new MfaProperties(KEY).toString()).isEqualTo("MfaProperties[redacted]");
    }
}
