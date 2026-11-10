package br.com.sergio.sapiensia.product;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Test double of the product file storage: it delegates to the disk storage until {@link #failPuts(boolean)} or
 * {@link #failDeletes(boolean)} turns a failure on, and remembers which keys currently exist.
 */
public class SwitchableProductFileStorage implements ProductFileStorage {

    private final ProductFileStorage delegate;
    private final Set<String> keys = ConcurrentHashMap.newKeySet();
    private volatile boolean failingPuts;
    private volatile boolean failingDeletes;
    private volatile CyclicBarrier gate;

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
        CyclicBarrier barrier = gate;
        if (barrier != null) {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProductFileStorageException("gate interrupted");
            } catch (BrokenBarrierException | TimeoutException e) {
                throw new ProductFileStorageException("gate broken");
            }
        }
    }

    /**
     * Makes each put wait, after storing, until {@code parties} puts have stored: all callers then leave together. It
     * lets a test force several uploads to be in flight before any of them swaps its pointer.
     */
    public void gatePuts(int parties) {
        this.gate = parties > 0 ? new CyclicBarrier(parties) : null;
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
