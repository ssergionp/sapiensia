package br.com.sergio.sapiensia.customer;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * The browsers an account has already logged in from, told apart by the random token of the device cookie. Only the
 * SHA-256 hash of the token is stored, per account: the cookie alone grants no access.
 */
@Component
class KnownDevices {

    private static final Pattern VALID_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** The token to put in the cookie, and whether the owner must be told about this login. */
    record DeviceCheck(String token, boolean notifyOwner) {}

    private final JdbcClient jdbc;

    KnownDevices(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True if the value has the shape of a token we issue (43 Base64 URL characters); anything else counts as absent. */
    static boolean isWellFormed(@Nullable String token) {
        return token != null && VALID_TOKEN.matcher(token).matches();
    }

    /**
     * Registers the device for the account after a correct login. The first login of an account never notifies; a
     * later login notifies unless the cookie token is already registered for this account.
     */
    DeviceCheck recognize(UUID customerId, @Nullable String cookieToken) {
        boolean wellFormed = isWellFormed(cookieToken);
        if (wellFormed) {
            int seen = jdbc.sql("update customer_devices set last_seen_at = now()"
                            + " where customer_id = :customerId and token_hash = :hash")
                    .param("customerId", customerId)
                    .param("hash", EmailConfirmationTokens.hash(cookieToken))
                    .update();
            if (seen == 1) {
                return new DeviceCheck(cookieToken, false);
            }
        }
        String token = wellFormed ? cookieToken : PasswordResetTokens.newToken();
        jdbc.sql("insert into customer_devices (customer_id, token_hash) values (:customerId, :hash)"
                        + " on conflict do nothing")
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
}
