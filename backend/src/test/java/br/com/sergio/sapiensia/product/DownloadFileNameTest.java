package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DownloadFileNameTest {

    @Test
    @DisplayName("FR-034: o nome do arquivo vem do título, em minúsculas e com hífens")
    void shouldTurnTheTitleIntoALowerCaseSlug() {
        assertThat(DownloadFileName.of("Guia de Contratos")).isEqualTo("guia-de-contratos.pdf");
    }

    @Test
    @DisplayName("FR-034: o nome do arquivo não tem acentos nem pontuação")
    void shouldDropAccentsAndPunctuation() {
        assertThat(DownloadFileName.of("Locação: 2ª edição!")).isEqualTo("locacao-2-edicao.pdf");
    }

    @Test
    @DisplayName("FR-034: título sem letras nem números vira arquivo.pdf")
    void shouldFallBackWhenNothingIsLeft() {
        assertThat(DownloadFileName.of("???")).isEqualTo("arquivo.pdf");
    }

    @Test
    @DisplayName("FR-034: o nome tem no máximo 60 caracteres mais .pdf, sem hífen no fim")
    void shouldCutAt60CharactersWithoutATrailingHyphen() {
        assertThat(DownloadFileName.of("a".repeat(200))).isEqualTo("a".repeat(60) + ".pdf");
        // the cut falls right after a hyphen
        assertThat(DownloadFileName.of("a".repeat(60) + " bbb")).isEqualTo("a".repeat(60) + ".pdf");
        assertThat(DownloadFileName.of("a".repeat(59) + " bbb")).isEqualTo("a".repeat(59) + ".pdf");
    }

    @Test
    @DisplayName("FR-034: o nome nunca contém barra, aspas, espaço nem caractere de controle")
    void shouldNeverContainUnsafeCharacters() {
        String name = DownloadFileName.of("../a\\b\"c d\te\nf\u0000g/..");
        assertThat(name).doesNotContain("/", "\\", "\"", " ").doesNotContainPattern("\\p{Cntrl}");
        assertThat(name).matches("[a-z0-9-]+\\.pdf");
    }
}
