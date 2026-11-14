package br.com.sergio.sapiensia.payment;

import java.util.UUID;

/** The body of a created or reused Order: only its id. The Pix code is read with {@code GET}, never returned here. */
record OrderCreated(UUID id) {}
