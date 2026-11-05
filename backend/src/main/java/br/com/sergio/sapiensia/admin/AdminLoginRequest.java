package br.com.sergio.sapiensia.admin;

/**
 * No validation annotations on purpose: a bad or missing field is just a failed login (same 401), as for the
 * customer.
 */
record AdminLoginRequest(String email, String password) {

    // never the e-mail or the password
    @Override
    public String toString() {
        return "AdminLoginRequest[redacted]";
    }
}
