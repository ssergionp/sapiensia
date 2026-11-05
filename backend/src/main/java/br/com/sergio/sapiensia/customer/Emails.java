package br.com.sergio.sapiensia.customer;

import java.util.Locale;

/** The one way an e-mail is normalized: for accounts, lookups, the configured Administrator and limiter keys. */
public final class Emails {

    private Emails() {}

    /** Trimmed and lower-cased (locale independent); null becomes the empty string. */
    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
