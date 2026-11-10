package br.com.sergio.sapiensia.product;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double of the product file storage: it delegates to the disk storage until {@link #failPuts(boolean)} or
 * {@link #failDeletes(boolean)} turns a failure on, and remembers which keys currently exist.
 */
public class SwitchableProductFileStorage implements ProductFileStorage {

    private final ProductFileStorage delegate;
    private final Set<String> keys = ConcurrentHashMap.newKeySet();
    private volatile boolean failingPuts;
    private volatile boolean failingDeletes;

    SwitchableProductFileStorage(ProductFileStorage delegate) {
        this.delegate = delegate;
    }

    public void failPuts(boolean failing) {
        this.failingPuts = failing;
    }

    public void failDeletes(boolean failing) {
        this.failingDeletes = failing;
    }

    /** Keys stored and not deleted yet. */
    public Set<String> keys() {
        return Set.copyOf(keys);
    }

    @Override
    public void put(String key, Path file, long sizeBytes) {
        if (failingPuts) {
            throw new ProductFileStorageException("storage is down");
        }
        delegate.put(key, file, sizeBytes);
        keys.add(key);
    }

    @Override
    public void delete(String key) {
        if (failingDeletes) {
            throw new ProductFileStorageException("storage is down");
        }
        delegate.delete(key);
        keys.remove(key);
    }
}
