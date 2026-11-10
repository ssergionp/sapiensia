package br.com.sergio.sapiensia.product;

import java.time.Instant;

/** The current file of a Product as the panel sees it. Never the object key or any URL. */
record ProductFileInfo(long sizeBytes, int pageCount, Instant uploadedAt) {}
