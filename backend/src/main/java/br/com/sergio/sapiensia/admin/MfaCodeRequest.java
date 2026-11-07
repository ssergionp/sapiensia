package br.com.sergio.sapiensia.admin;

/**
 * The 6-digit code of the authenticator app. No validation annotations on purpose: a malformed code is just a wrong
 * code (400 CODE_INVALID) and counts against the limit like one.
 */
record MfaCodeRequest(String code) {

    // never the code
    @Override
    public String toString() {
        return "MfaCodeRequest[redacted]";
    }
}
