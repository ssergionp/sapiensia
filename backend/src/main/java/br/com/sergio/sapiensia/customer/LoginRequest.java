package br.com.sergio.sapiensia.customer;

/** No validation annotations on purpose: any bad or missing field is just a failed login (same 401). */
record LoginRequest(String email, String password) {}
