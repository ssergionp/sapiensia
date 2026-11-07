package br.com.sergio.sapiensia.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class TotpTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void rfc6238AppendixBVectors() {
        assertThat(Totp.code(RFC_SECRET, 59L / 30, 8)).isEqualTo("94287082");
        assertThat(Totp.code(RFC_SECRET, 1111111109L / 30, 8)).isEqualTo("07081804");
        assertThat(Totp.code(RFC_SECRET, 1111111111L / 30, 8)).isEqualTo("14050471");
        assertThat(Totp.code(RFC_SECRET, 1234567890L / 30, 8)).isEqualTo("89005924");
        assertThat(Totp.code(RFC_SECRET, 2000000000L / 30, 8)).isEqualTo("69279037");
        assertThat(Totp.code(RFC_SECRET, 20000000000L / 30, 8)).isEqualTo("65353130");
    }

    @Test
    void sixDigitsKeepLeadingZeros() {
        assertThat(Totp.code(RFC_SECRET, 1111111109L / 30, 6)).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, 59L / 30, 6)).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, 20000000000L / 30, 6)).isEqualTo("353130");
    }

    @Test
    void stepIsEpochSecondDividedByThirtyRoundedDown() {
        assertThat(Totp.stepAt(Instant.ofEpochSecond(59))).isEqualTo(1);
        assertThat(Totp.stepAt(Instant.ofEpochSecond(60))).isEqualTo(2);
    }

    @Test
    void acceptsPreviousCurrentAndNextStepAndReturnsTheMatchingStep() {
        Instant now = Instant.ofEpochSecond(1_700_000_010L);
        long step = Totp.stepAt(now);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step - 1, 6), now))
                .isEqualTo(OptionalLong.of(step - 1));
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step, 6), now))
                .isEqualTo(OptionalLong.of(step));
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step + 1, 6), now))
                .isEqualTo(OptionalLong.of(step + 1));
    }

    @Test
    void rejectsTwoStepsAwayInBothDirections() {
        Instant now = Instant.ofEpochSecond(1_700_000_010L);
        long step = Totp.stepAt(now);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step - 2, 6), now))
                .isEmpty();
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.code(RFC_SECRET, step + 2, 6), now))
                .isEmpty();
    }

    @Test
    void rejectsMalformedCodes() {
        Instant now = Instant.ofEpochSecond(1_700_000_010L);
        String valid = Totp.code(RFC_SECRET, Totp.stepAt(now), 6);
        List<String> bad = new ArrayList<>();
        bad.add(null);
        bad.add("");
        bad.add(valid.substring(0, 5));
        bad.add(valid + "0");
        bad.add("abcdef");
        bad.add(valid.substring(0, 3) + " " + valid.substring(3));
        bad.add(" " + valid.substring(0, 5));
        bad.add(valid.substring(0, 5) + " ");
        // the real valid code in Arabic-Indic digits: only the ASCII range check can reject it
        bad.add(arabicIndic(valid));
        bad.add("+12345");
        for (String code : bad) {
            assertThat(Totp.matchingStep(RFC_SECRET, code, now))
                    .as("code %s", code)
                    .isEmpty();
        }
    }

    @Test
    void matchingStepChecksAllThreeCandidatesWithoutEarlyExit() {
        long step = 1000;
        List<Long> asked = new ArrayList<>();
        // The first candidate already matches; the others must still be computed.
        OptionalLong result = Totp.matchingStep("123456", step, s -> {
            asked.add(s);
            return "123456";
        });
        assertThat(result).isEqualTo(OptionalLong.of(step + 1));
        assertThat(asked).containsExactly(step - 1, step, step + 1);
    }

    @Test
    void matchingStepReturnsTheHighestStepWhenTheCodeIsValidInMoreThanOne() {
        long step = 1000;
        // the same code at step and step + 1: returning the older one would let it be replayed one step later
        assertThat(Totp.matchingStep("123456", step, s -> s >= step ? "123456" : "000000"))
                .isEqualTo(OptionalLong.of(step + 1));
        // the same code at step - 1 and step only
        assertThat(Totp.matchingStep("123456", step, s -> s <= step ? "123456" : "000000"))
                .isEqualTo(OptionalLong.of(step));
    }

    @Test
    void newSecretHas20BytesAndBase32Has32CharsWithoutPadding() {
        byte[] a = Totp.newSecret();
        byte[] b = Totp.newSecret();
        assertThat(a).hasSize(20);
        assertThat(a).isNotEqualTo(b);
        assertThat(Totp.toBase32(a)).hasSize(32).matches("[A-Z2-7]+");
    }

    @Test
    void otpauthUriForKnownSecretAndEmailWithPlusAndAt() {
        String uri = Totp.otpauthUri("SapiensIA", "john+tag@example.com", RFC_SECRET);
        assertThat(uri)
                .isEqualTo("otpauth://totp/SapiensIA:john%2Btag%40example.com"
                        + "?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" // gitleaks:allow
                        + "&issuer=SapiensIA&algorithm=SHA1&digits=6&period=30");
    }

    @Test
    void toBase32StripsThePaddingOfAnInputThatNeedsIt() {
        // 19 bytes = 152 bits = 31 Base32 characters, padded to 32 with one "="
        assertThat(Totp.toBase32(new byte[19])).isEqualTo("A".repeat(31));
    }

    @Test
    void codeUsesAsciiDigitsWhateverTheDefaultLocale() {
        Locale before = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG-u-nu-arab"));
            assertThat(Totp.code(RFC_SECRET, 59L / 30, 6)).isEqualTo("287082");
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, before);
        }
    }

    @Test
    void codeRefusesDigitsOutsideOneToNine() {
        for (int digits : new int[] {0, -1, 10}) {
            assertThatThrownBy(() -> Totp.code(RFC_SECRET, 1, digits))
                    .as("digits %d", digits)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static String arabicIndic(String asciiDigits) {
        StringBuilder out = new StringBuilder();
        for (char c : asciiDigits.toCharArray()) {
            out.append((char) ('٠' + (c - '0')));
        }
        return out.toString();
    }
}
