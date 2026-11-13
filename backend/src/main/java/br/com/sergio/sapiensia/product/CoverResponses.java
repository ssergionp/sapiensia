package br.com.sergio.sapiensia.product;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The answer of the two cover routes (public and panel preview): the checked type, a strong ETag that is the stored
 * version and {@code private, no-cache}, so the browser keeps the image but asks every time. Nothing here logs a key.
 */
final class CoverResponses {

    private static final Logger log = LoggerFactory.getLogger(CoverResponses.class);

    private static final String PRIVATE_NO_CACHE = "private, no-cache";

    private CoverResponses() {}

    /** 304 when the client already has this version (the Storage is not touched), else 200 with the bytes. */
    static ResponseEntity<byte[]> serve(
            ProductCoverService service, StoredCover cover, String ifNoneMatch, UUID productId) {
        String etag = "\"" + cover.version() + "\"";
        if (matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_CACHE)
                    .build();
        }
        byte[] bytes;
        try {
            bytes = service.read(cover);
        } catch (ProductCoverMissingException e) {
            log.warn("Product cover object missing (product {})", productId);
            throw new ProductNotFoundException();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(cover.mediaType()))
                .contentLength(bytes.length)
                .eTag(etag)
                .header(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_CACHE)
                .body(bytes);
    }

    /** If-None-Match is a list of (possibly weak) tags or "*"; a weak comparison is what the header asks for. */
    private static boolean matches(String header, String etag) {
        if (header == null) {
            return false;
        }
        for (String candidate : header.split(",")) {
            String tag = candidate.strip();
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.equals("*") || tag.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
