package br.com.sergio.sapiensia.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditPropertiesTest {

    @Test
    @DisplayName("SEC-037: shouldAcceptTheRetentionFloorAndLonger")
    void shouldAcceptTheRetentionFloorAndLonger() {
        assertThat(new AuditProperties(Duration.ofDays(365)).retention()).isEqualTo(Duration.ofDays(365));
        assertThat(new AuditProperties(Duration.ofDays(730)).retention()).isEqualTo(Duration.ofDays(730));
    }

    @Test
    @DisplayName("SEC-037: shouldRejectARetentionBelowTheTriggerFloor")
    void shouldRejectARetentionBelowTheTriggerFloor() {
        assertThatThrownBy(() -> new AuditProperties(Duration.ofDays(364)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditProperties(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditProperties(Duration.ofDays(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SEC-037: shouldRejectAMissingRetention")
    void shouldRejectAMissingRetention() {
        assertThatThrownBy(() -> new AuditProperties(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
