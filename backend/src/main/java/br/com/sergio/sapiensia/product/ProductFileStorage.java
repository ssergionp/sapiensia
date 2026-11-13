package br.com.sergio.sapiensia.product;

import java.nio.file.Path;
import java.util.Optional;
import org.springframework.http.MediaType;

/** Where the product objects (PDFs and cover images) live: private Supabase buckets in production, a directory elsewhere. */
interface ProductFileStorage {

    /** Stores the file under the key with the given media type; never overwrites. Throws ProductFileStorageException on any failure. */
    void put(String key, Path file, long sizeBytes, MediaType type);

    /**
     * Reads the object; empty when it does not exist. Reads at most {@code CoverImageValidator.MAX_BYTES + 1} bytes and
     * throws ProductFileStorageException on any failure, or when the object is bigger than {@code MAX_BYTES}.
     */
    Optional<byte[]> get(String key);

    /** Deletes the object; a missing object is not an error. Throws ProductFileStorageException on failure. */
    void delete(String key);
}
