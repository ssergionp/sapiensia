package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.security.Tokens;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.bouncycastle.util.encoders.Base32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR-0004: the one-time recovery codes, without the database. */
class RecoveryCodesTest {

    private static final String CODE = "ABCDEFGHIJKLMNOPQRSTUVWX";

    @Test
    @DisplayName("ADR-0004: gera 10 códigos distintos de 24 caracteres Base32, cada um com 120 bits")
    void shouldGenerateTenDistinctCodesOf120Bits() {
        List<String> codes = RecoveryCodes.generate();

        assertThat(codes).hasSize(10).doesNotHaveDuplicates();
        for (String code : codes) {
            assertThat(code).matches("[A-Z2-7]{24}");
            assertThat(Base32.decode(code)).hasSize(15);
            assertThat(RecoveryCodes.normalize(code)).hasValue(code);
        }
    }

    @Test
    @DisplayName("ADR-0004: gerações seguidas não repetem códigos")
    void shouldNotRepeatCodesAcrossGenerations() {
        var all = new HashSet<String>();
        for (int i = 0; i < 20; i++) {
            all.addAll(RecoveryCodes.generate());
        }

        assertThat(all).hasSize(200);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ABCD-EFGH-IJKL-MNOP-QRST-UVWX",
                "abcd-efgh-ijkl-mnop-qrst-uvwx",
                "ABCD EFGH IJKL MNOP QRST UVWX",
                "  abcdEFGH-ijkl mnop-QRSTuvwx  ",
                "ABCDEFGHIJKLMNOPQRSTUVWX"
            })
    @DisplayName("ADR-0004: a entrada ignora hífens e espaços e aceita minúsculas")
    void shouldNormalizeHyphensSpacesAndLowerCase(String typed) {
        assertThat(RecoveryCodes.normalize(typed)).hasValue(CODE);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "",
                "ABCDEFGHIJKLMNOPQRSTUVW",
                "ABCDEFGHIJKLMNOPQRSTUVWXY",
                "ABCDEFGHIJKLMNOPQRSTUVW0",
                "ABCDEFGHIJKLMNOPQRSTUVW1",
                "ABCDEFGHIJKLMNOPQRSTUVW8",
                "ABCDEFGHIJKLMNOPQRSTUVW=",
                "ABCDEFGHIJKLMNOPQRSTUVWÉ",
                "ABCD_EFGH_IJKL_MNOP_QRST_UVWX",
                "123456"
            })
    @DisplayName("ADR-0004: recusa nulo, tamanho errado e caractere fora do Base32")
    void shouldRefuseMalformedCodes(String typed) {
        assertThat(RecoveryCodes.normalize(typed)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ı", "ſ", "ß", "１", " ", " ", "K"})
    @DisplayName("ADR-0004: recusa caractere não ASCII, que o toUpperCase dobraria para A-Z ou que não é separador")
    void shouldRefuseNonAsciiCharacters(String odd) {
        // each one replaces a letter (or sits inside the code) of an otherwise valid code
        assertThat(RecoveryCodes.normalize(CODE.substring(0, 8) + odd + CODE.substring(9)))
                .isEmpty();
        assertThat(RecoveryCodes.normalize(CODE.substring(0, 8) + odd + CODE.substring(8)))
                .isEmpty();
    }

    @Test
    @DisplayName("ADR-0004: recusa entrada gigante, mesmo feita só de hífens e um código válido")
    void shouldRefuseAHugeInput() {
        assertThat(RecoveryCodes.normalize("-".repeat(10_000) + CODE)).isEmpty();
        assertThat(RecoveryCodes.normalize(CODE.repeat(1_000))).isEmpty();
    }

    @Test
    @DisplayName("ADR-0004: acha o código na primeira e na última posição, e só o do hash certo")
    void shouldMatchInTheFirstAndTheLastPosition() {
        List<String> codes = RecoveryCodes.generate();
        List<RecoveryCodes.Unused> unused = new ArrayList<>();
        for (String code : codes) {
            unused.add(new RecoveryCodes.Unused(UUID.randomUUID(), Tokens.hash(code)));
        }

        assertThat(RecoveryCodes.matching(unused, codes.getFirst()))
                .hasValue(unused.getFirst().id());
        assertThat(RecoveryCodes.matching(unused, codes.getLast()))
                .hasValue(unused.getLast().id());
        assertThat(RecoveryCodes.matching(unused, CODE)).isEmpty();
        assertThat(RecoveryCodes.matching(List.of(), codes.getFirst())).isEmpty();
    }

    @Test
    @DisplayName("SEC-036: o toString de um código guardado não mostra o hash")
    void shouldRedactTheHashInToString() {
        String hash = Tokens.hash(CODE);

        assertThat(new RecoveryCodes.Unused(UUID.randomUUID(), hash).toString()).doesNotContain(hash);
        assertThat(new RecoveryCodeList(List.of(CODE)).toString()).doesNotContain(CODE);
    }
}
