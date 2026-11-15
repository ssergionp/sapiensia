package br.com.sergio.sapiensia.product;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;

/**
 * Test double of the product file storage: it delegates to the disk storage until {@link #failPuts(boolean)} or
 * {@link #failDeletes(boolean)} turns a failure on, and remembers which keys currently exist.
 */
public class SwitchableProductFileStorage implements ProductFileStorage {

    private final ProductFileStorage delegate;
    private final Set<String> keys = ConcurrentHashMap.newKeySet();
    private volatile boolean failingPuts;
    private volatile boolean failingDeletes;
    private volatile boolean failingGets;
    private volatile boolean failingSigns;
    private final AtomicInteger signCalls = new AtomicInteger();
    private volatile CountDownLatch signHold;
    private volatile CyclicBarrier gate;
    private volatile Pause pause;

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
    public void put(String key, Path file, long sizeBytes, MediaType type) {
        if (failingPuts) {
            throw new ProductFileStorageException("storage is down");
        }
        delegate.put(key, file, sizeBytes, type);
        keys.add(key);
        Pause held = pause;
        if (held != null) {
            held.hold();
        }
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

    /** A put that has stored its object and now waits: the test sees it arrive, then lets it go. */
    public static final class Pause {

        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void hold() {
            reached.countDown();
            try {
                if (!released.await(20, TimeUnit.SECONDS)) {
                    throw new ProductFileStorageException("pause never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProductFileStorageException("pause interrupted");
            }
        }

        /** Waits until a put is held; false if none arrived in time. */
        public boolean awaitReached() throws InterruptedException {
            return reached.await(20, TimeUnit.SECONDS);
        }

        public void release() {
            released.countDown();
        }
    }

    /** Makes the next puts wait, after storing, until the returned {@link Pause} is released. */
    public Pause pausePuts() {
        Pause created = new Pause();
        this.pause = created;
        return created;
    }

    /** Stops pausing puts. */
    public void resumePuts() {
        this.pause = null;
    }

    public void failGets(boolean failing) {
        this.failingGets = failing;
    }

    @Override
    public Optional<byte[]> get(String key) {
        if (failingGets) {
            throw new ProductFileStorageException("storage is down");
        }
        return delegate.get(key);
    }

    public void failSigns(boolean failing) {
        this.failingSigns = failing;
    }

    /** Number of signing calls so far (cumulative: tests assert a delta from a baseline). */
    public int signCalls() {
        return signCalls.get();
    }

    /** Makes each signing wait on the latch, after being counted; {@code null} turns it off. */
    public void holdSigns(CountDownLatch release) {
        this.signHold = release;
    }

    @Override
    public Optional<String> signedDownloadUrl(String key, java.time.Duration ttl, String fileName) {
        signCalls.incrementAndGet();
        CountDownLatch held = signHold;
        if (held != null) {
            try {
                if (!held.await(20, TimeUnit.SECONDS)) {
                    throw new ProductFileStorageException("sign hold never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProductFileStorageException("sign hold interrupted");
            }
        }
        if (failingSigns) {
            throw new ProductFileStorageException("storage is down");
        }
        return delegate.signedDownloadUrl(key, ttl, fileName);
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
