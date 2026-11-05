package br.com.sergio.sapiensia.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The panel's "admin:"-prefixed account keys live in the same limiter, so they share its entry cap (#11). */
class AdminAccountKeysLimiterTest {

    private static final String IP = "203.0.113.1";
    private static final LoginLimitProperties PROPERTIES = new LoginLimitProperties(
            5, Duration.ofMinutes(15), Duration.ofMinutes(15), 1000, Duration.ofMinutes(15), "");

    private static String adminKey(String email) {
        return "admin:" + Emails.normalize(email);
    }

    @Test
    @DisplayName("SEC-002: grafias do mesmo e-mail do painel contam no mesmo balde")
    void shouldCountEverySpellingOfAnAdminEmailInOneBucket() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(PROPERTIES, Clock.systemUTC());
        String[] spellings = {"Admin@X.com", " admin@x.com", "\tADMIN@x.com\t", "admin@x.com ", "admin@x.com"};
        for (String spelling : spellings) {
            assertThat(limiter.admit(adminKey(spelling), IP).allowed()).isTrue();
        }

        assertThat(limiter.trackedAccounts()).isEqualTo(1);
        assertThat(limiter.admit(adminKey(" ADMIN@x.com"), IP).allowed()).isFalse();
    }

    @Test
    @DisplayName("SEC-002: as chaves admin: obedecem ao mesmo teto de entradas do #11")
    void shouldApplyTheSameEntryCapToAdminKeys() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(PROPERTIES, Clock.systemUTC(), 3);
        for (String email : new String[] {"a@x.com", "b@x.com", "c@x.com"}) {
            assertThat(limiter.admit(adminKey(email), IP).allowed()).isTrue();
        }

        // new keys are refused, whatever their spelling, and evict nobody
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.admit(adminKey(" Flood" + i + "@x.com"), IP).allowed())
                    .isFalse();
        }
        assertThat(limiter.trackedAccounts()).isEqualTo(3);
        // a customer key is a new key too: the cap is shared
        assertThat(limiter.admit("customer@x.com", IP).allowed()).isFalse();
        // an already tracked key (another spelling of it) is still served
        assertThat(limiter.admit(adminKey("\tA@X.com"), IP).allowed()).isTrue();
    }
}
