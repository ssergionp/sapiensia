package br.com.sergio.sapiensia.admin;

/**
 * The password and the 6-digit code of the authenticator app. No validation annotations on purpose: a missing or
 * malformed field is just a failed reauthentication (400 CREDENTIALS_INVALID), as for the login.
 */
record ReauthenticationRequest(String password, String code) {

    // never the password or the code
    @Override
    public String toString() {
        return "ReauthenticationRequest[redacted]";
    }
}
