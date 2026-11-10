package br.com.sergio.sapiensia.product;

import java.nio.file.Path;

/** Where the product PDFs live: a private Supabase bucket in production, a local directory elsewhere. */
interface ProductFileStorage {

    /** Stores the file under the key; never overwrites. Throws ProductFileStorageException on any failure. */
    void put(String key, Path file, long sizeBytes);

    /** Deletes the object; a missing object is not an error. Throws ProductFileStorageException on failure. */
    void delete(String key);
}
