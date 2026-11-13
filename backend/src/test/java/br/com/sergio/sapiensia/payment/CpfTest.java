package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CpfTest {

    @ParameterizedTest
    @ValueSource(strings = {"52998224725", "529.982.247-25", " 111.444.777-35 ", "529.982.\n247-25"})
    @DisplayName("SEC-033: shouldAcceptValidCpfWithOrWithoutFormatting")
    void shouldAcceptValidCpf(String raw) {
        assertThat(Cpf.parse(raw)).isPresent();
    }

    @Test
    @DisplayName("SEC-033: shouldKeepOnlyTheDigits")
    void shouldKeepOnlyTheDigits() {
        assertThat(Cpf.parse(" 529.982.247-25 ").orElseThrow().digits()).isEqualTo("52998224725");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"52998224724", "11111111111", "5299822472", "529982247255", "5299822472a", "abc", "", "   "})
    @NullSource
    @DisplayName("SEC-033: shouldRefuseInvalidCpf")
    void shouldRefuseInvalidCpf(String raw) {
        assertThat(Cpf.parse(raw)).isEmpty();
    }

    @Test
    @DisplayName("SEC-033: shouldNeverPrintTheNumber")
    void shouldNeverPrintTheNumber() {
        Cpf cpf = Cpf.parse("529.982.247-25").orElseThrow();
        assertThat(cpf.toString())
                .isEqualTo("Cpf[redacted]")
                .doesNotContain("529")
                .doesNotContain("25");
    }
}
