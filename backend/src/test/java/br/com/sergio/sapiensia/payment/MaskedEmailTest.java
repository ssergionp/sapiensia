package br.com.sergio.sapiensia.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaskedEmailTest {

    @Test
    @DisplayName("FR-020: keeps the first letter and the domain, always with three asterisks")
    void shouldKeepFirstLetterAndDomain() {
        assertThat(MaskedEmail.of("sergio@gmail.com")).isEqualTo("s***@gmail.com");
        assertThat(MaskedEmail.of("a@x.com")).isEqualTo("a***@x.com");
    }

    @Test
    @DisplayName("FR-020: without an at sign, or with nothing before it, shows only asterisks")
    void shouldShowOnlyAsterisksWhenThereIsNoLocalPart() {
        assertThat(MaskedEmail.of("semarroba")).isEqualTo("***");
        assertThat(MaskedEmail.of("@x.com")).isEqualTo("***");
    }
}
