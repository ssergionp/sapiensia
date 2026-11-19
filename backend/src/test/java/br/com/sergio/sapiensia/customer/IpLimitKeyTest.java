package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IpLimitKeyTest {

    @ParameterizedTest
    @CsvSource({
        "203.0.113.7, 203.0.113.7",
        "2001:db8:1:2::1, 2001:db8:1:2:0:0:0:0/64",
        "2001:db8:1:2:aaaa:bbbb:cccc:dddd, 2001:db8:1:2:0:0:0:0/64",
        "2001:0db8:0001:0002::9, 2001:db8:1:2:0:0:0:0/64",
        "2001:db8:1:3::1, 2001:db8:1:3:0:0:0:0/64",
        "::1, 0:0:0:0:0:0:0:0/64",
        "::ffff:203.0.113.7, 203.0.113.7",
        "::ffff:cb00:7107, 203.0.113.7",
        "not:an:ip, not:an:ip"
    })
    @DisplayName("SEC-014: shouldBuildTheCountingKey")
    void shouldBuildTheCountingKey(String clientIp, String expected) {
        assertThat(IpLimitKey.of(clientIp)).isEqualTo(expected);
    }

    @Test
    @DisplayName("SEC-014: shouldNeverThrowAndAlwaysReturnAKey")
    void shouldNeverThrowAndAlwaysReturnAKey() {
        assertThat(IpLimitKey.of(null)).isEmpty();
        assertThat(IpLimitKey.of("")).isEmpty();
        assertThat(IpLimitKey.of("fe80::1%eth0")).isNotEmpty();
        assertThat(IpLimitKey.of("[2001:db8::1]")).isNotEmpty();
    }
}
