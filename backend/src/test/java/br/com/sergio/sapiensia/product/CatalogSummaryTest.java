package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogSummaryTest {

    private static final String ELLIPSIS = "\u2026";

    private static String beforeEllipsis(String summary) {
        assertThat(summary).endsWith(ELLIPSIS);
        return summary.substring(0, summary.length() - 1);
    }

    @Test
    @DisplayName("FR-016: a description of up to 200 characters comes back as it is")
    void shouldKeepAShortDescription() {
        assertThat(CatalogSummary.of("Texto curto.")).isEqualTo("Texto curto.");
        String exact = "a".repeat(200);
        assertThat(CatalogSummary.of(exact)).isEqualTo(exact);
    }

    @Test
    @DisplayName("FR-016: spaces and line breaks become one space and the ends are trimmed")
    void shouldCollapseWhitespace() {
        assertThat(CatalogSummary.of("Linha um\n\n  Linha   dois")).isEqualTo("Linha um Linha dois");
        assertThat(CatalogSummary.of("  \n Texto \t")).isEqualTo("Texto");
    }

    @Test
    @DisplayName("FR-016: a long description is cut at the last space within 200 characters")
    void shouldCutAtTheLastSpace() {
        String description = "palavra um dois tres quatro cinco seis sete oito nove dez ".repeat(5);
        String normalized = description.strip();
        assertThat(normalized.length()).isGreaterThan(200);

        String before = beforeEllipsis(CatalogSummary.of(description));

        assertThat(before.length()).isLessThanOrEqualTo(200);
        assertThat(before).doesNotEndWith(" ");
        assertThat(normalized).startsWith(before);
        assertThat(normalized.charAt(before.length())).isEqualTo(' ');
    }

    @Test
    @DisplayName("FR-016: A3 a text without spaces is cut at 200 characters")
    void shouldCutAtTheLimitWithoutSpaces() {
        assertThat(CatalogSummary.of("a".repeat(250))).isEqualTo("a".repeat(200) + ELLIPSIS);
    }

    @Test
    @DisplayName("FR-016: A3 an emoji of two chars is never split")
    void shouldNotSplitASurrogatePair() {
        String description = "a".repeat(199) + "\uD83D\uDE00" + "b".repeat(50);
        assertThat(CatalogSummary.of(description)).isEqualTo("a".repeat(199) + ELLIPSIS);
    }

    @Test
    @DisplayName("FR-016: A3 a letter with a combining accent is never separated from it")
    void shouldNotSplitACombiningCharacter() {
        String description = "a".repeat(199) + "e\u0301" + "b".repeat(50);
        assertThat(CatalogSummary.of(description)).isEqualTo("a".repeat(199) + ELLIPSIS);
    }

    @Test
    @DisplayName("FR-016: A3 a ZWJ emoji family stays out whole, never in part")
    void shouldNotSplitAZwjSequence() {
        String family = "\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
        String description = "a".repeat(195) + family + "b".repeat(50);
        assertThat(CatalogSummary.of(description)).isEqualTo("a".repeat(195) + ELLIPSIS);
    }

    @Test
    @DisplayName("FR-016: A3 a word is not broken in the middle")
    void shouldNotBreakAWord() {
        String before = beforeEllipsis(CatalogSummary.of("palavra ".repeat(40)));

        assertThat(before).endsWith("palavra");
        assertThat(before.length()).isLessThanOrEqualTo(200);
        assertThat(before).isEqualTo(("palavra ".repeat(25)).strip());
    }
}
