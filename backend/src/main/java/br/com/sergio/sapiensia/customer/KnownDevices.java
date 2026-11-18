package br.com.sergio.sapiensia.customer;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The browsers an account has already logged in from, told apart by the random token of the device cookie. Only the
 * SHA-256 hash of the token is stored, per account: the cookie alone grants no access.
 */
@Component
class KnownDevices {

    private static final Pattern VALID_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** The token to put in the cookie, and whether the owner must be told about this login. */
    record DeviceCheck(String token, boolean notifyOwner) {
        static final DeviceCheck NOT_REGISTERED = new DeviceCheck(null, false);
    }

    /** How long the device cookie lives; a device not seen for this long is forgotten by the daily cleanup. */
    static final Duration COOKIE_MAX_AGE = Duration.ofDays(365);

    private final JdbcClient jdbc;

    KnownDevices(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * True if the value has the shape of a token we issue (43 Base64 URL characters); anything else, null included,
     * counts as absent.
     */
    static boolean isWellFormed(String token) {
        return token != null && VALID_TOKEN.matcher(token).matches();
    }

    /**
     * Registers the device for the account after a correct login, in one transaction. The customer row is locked first
     * and must still have the {@code sessionVersion} read at login: if the password changed (or the account is gone)
     * since then, nothing is recorded and {@link DeviceCheck#NOT_REGISTERED} comes back. A cookie token already
     * registered for this account is kept; any other login gets a new token generated here, never the cookie value.
     * The first login of an account never notifies; a later login notifies unless the cookie token was known.
     * {@code cookieToken} is null when the browser sent no device cookie.
     */
    @Transactional
    DeviceCheck recognize(UUID customerId, int sessionVersion, String cookieToken) {
        // the same lock as a plain update: waits for a password change and other logins of this account, not for an
        // insert that references the customer
        boolean current = jdbc.sql("select 1 from customers where id = :customerId"
                        + " and session_version = :sessionVersion for no key update")
                .param("customerId", customerId)
                .param("sessionVersion", sessionVersion)
                .query(Integer.class)
                .optional()
                .isPresent();
        if (!current) {
            return DeviceCheck.NOT_REGISTERED;
        }
        if (isWellFormed(cookieToken)) {
            int seen = jdbc.sql("update customer_devices set last_seen_at = now()"
                            + " where customer_id = :customerId and token_hash = :hash")
                    .param("customerId", customerId)
                    .param("hash", EmailConfirmationTokens.hash(cookieToken))
                    .update();
            if (seen == 1) {
                return new DeviceCheck(cookieToken, false);
            }
        }
        String token = PasswordResetTokens.newToken();
        jdbc.sql("insert into customer_devices (customer_id, token_hash) values (:customerId, :hash)")
                .param("customerId", customerId)
                .param("hash", EmailConfirmationTokens.hash(token))
                .update();
        // the conditional update lets exactly one of two simultaneous first logins be "the first"
        int firstLogin = jdbc.sql("update customers set first_login_at = now()"
                        + " where id = :customerId and first_login_at is null")
                .param("customerId", customerId)
                .update();
        return new DeviceCheck(token, firstLogin == 0);
    }

    /** Daily cleanup: forgets devices not seen for {@link #COOKIE_MAX_AGE}, skipping rows a login holds locked. */
    int deleteNotSeenWithinCookieMaxAge() {
        // "skip locked" never waits: a device a login is touching stays for the next night, so no lock cycle
        return jdbc.sql("delete from customer_devices where (customer_id, token_hash) in ("
                        + "select customer_id, token_hash from customer_devices"
                        + " where last_seen_at < now() - make_interval(secs => :seconds) for update skip locked)")
                .param("seconds", COOKIE_MAX_AGE.toSeconds())
                .update();
    }
}
