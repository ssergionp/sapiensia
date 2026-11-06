package br.com.sergio.sapiensia.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokensTest {

    @Test
    @DisplayName("SEC-007: o hash é o mesmo SHA-256 em hexadecimal de antes, compatível com os tokens já gravados")
    void shouldHashExactlyLikeTheTokensAlreadyStored() {
        // reference values computed outside this code base (.NET SHA256 over the UTF-8 bytes)
        assertThat(Tokens.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Tokens.hash("hT0fK1d3Qx9pXr2mV5nB8cJ7wZ4yL6sA0eG1uI3oP5q"))
                .isEqualTo("caa09ca26faf6b06601964360414a72ac2bf177b9458770e11679c4366de0a32");
    }

    @Test
    @DisplayName("SEC-007: o token novo tem 32 bytes aleatórios em Base64 URL sem padding (43 caracteres)")
    void shouldIssueRandom43CharacterUrlSafeTokens() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String token = Tokens.newToken();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            seen.add(token);
        }
        assertThat(seen).hasSize(100);
    }
}
