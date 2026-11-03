package br.com.sergio.sapiensia.customer;

import java.time.Instant;
import java.util.UUID;

/** What the login needs to know about an account: read by a projection, so the entity stays untouched. */
record CustomerCredentials(UUID id, String email, String passwordHash, Instant emailConfirmedAt) {}
