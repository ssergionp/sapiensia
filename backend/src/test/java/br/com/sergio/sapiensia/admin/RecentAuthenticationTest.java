package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecentAuthenticationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final RecentAuthentication recent = new RecentAuthentication(Clock.fixed(NOW, ZoneOffset.UTC), WINDOW);

    private static AdministratorPrincipal principal(Instant passwordVerifiedAt, Instant totpVerifiedAt) {
        return new AdministratorPrincipal(
                UUID.randomUUID(),
                "painel@example.com",
                0,
                EnumSet.allOf(Capability.class),
                passwordVerifiedAt,
                totpVerifiedAt);
    }

    @Test
    @DisplayName(
            "SEC-008: RecentAuthentication aceita até a janela e recusa 1 s depois; fator TOTP velho também recusa")
    void shouldAcceptUpToTheWindowAndRefuseOneSecondLater() {
        Instant edge = NOW.minus(WINDOW);

        assertThat(recent.isRecent(principal(NOW, NOW))).isTrue();
        assertThat(recent.isRecent(principal(edge.plusSeconds(1), edge.plusSeconds(1))))
                .isTrue();
        assertThat(recent.isRecent(principal(edge.minusSeconds(1), edge.minusSeconds(1))))
                .isFalse();
        // each factor alone is enough to refuse
        assertThat(recent.isRecent(principal(NOW, edge.minusSeconds(1)))).isFalse();
        assertThat(recent.isRecent(principal(edge.minusSeconds(1), NOW))).isFalse();
    }

    @Test
    @DisplayName("SEC-008: janela zero ou negativa para a aplicação na subida")
    void shouldRefuseANonPositiveWindow() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThatThrownBy(() -> new RecentAuthentication(clock, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RecentAuthentication(clock, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("SEC-008: ReauthenticationRequest não mostra senha nem código no toString")
    void shouldKeepThePasswordAndTheCodeOutOfToString() {
        var request = new ReauthenticationRequest("senha-do-painel-123", "654321"); // gitleaks:allow

        assertThat(request.toString()).doesNotContain("senha-do-painel-123").doesNotContain("654321");
    }
}
