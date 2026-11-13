package br.com.sergio.sapiensia.product;

/** Where the bytes of a cover live and what they are; never leaves the server. */
record StoredCover(String objectKey, String mediaType, String version) {}
