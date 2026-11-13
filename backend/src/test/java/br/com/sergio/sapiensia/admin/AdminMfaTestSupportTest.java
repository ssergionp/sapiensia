package br.com.sergio.sapiensia.admin;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.security.Totp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AdminMfaTestSupportTest {

    @Test
    void firstCodeNotInAcceptsTwoEqualCodes() {
        assertThat(AdminMfaTestSupport.firstCodeNotIn("000000", "000000", "000001"))
                .isEqualTo("000002");
    }

    @Test
    void firstCodeNotInAcceptsThreeEqualCodes() {
        assertThat(AdminMfaTestSupport.firstCodeNotIn("000000", "000000", "000000"))
                .isEqualTo("000001");
    }

    @Test
    void wrongCodeAtIsNoneOfTheThreeAcceptedCodes() {
        byte[] secret = Totp.newSecret();
        Instant at = Instant.now();

        String wrong = AdminMfaTestSupport.wrongCodeAt(secret, at);

        assertThat(wrong)
                .matches("\\d{6}")
                .isNotEqualTo(AdminMfaTestSupport.codeAt(secret, at.minus(Duration.ofSeconds(30))))
                .isNotEqualTo(AdminMfaTestSupport.codeAt(secret, at))
                .isNotEqualTo(AdminMfaTestSupport.codeAt(secret, at.plus(Duration.ofSeconds(30))));
    }
}
