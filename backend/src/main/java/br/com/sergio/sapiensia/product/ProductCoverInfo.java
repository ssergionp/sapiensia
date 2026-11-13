package br.com.sergio.sapiensia.product;

import java.time.Instant;

/** The current cover of a Product as the panel sees it; the version changes with the bytes. */
record ProductCoverInfo(String mediaType, int sizeBytes, int width, int height, String version, Instant uploadedAt) {}
