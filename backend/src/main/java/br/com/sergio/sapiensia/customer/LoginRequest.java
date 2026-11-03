package br.com.sergio.sapiensia.customer;

/**
 * No validation annotations on purpose: a bad or missing field is just a failed login (same 401). A body that is not
 * valid JSON never reaches the controller and gets Spring's 400.
 */
record LoginRequest(String email, String password) {}
