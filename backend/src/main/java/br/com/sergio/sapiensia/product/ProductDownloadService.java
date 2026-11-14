package br.com.sergio.sapiensia.product;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Issues the 60-second download link (FR-034). Order: find the owned file, sign, record, return. No transaction wraps
 * the Storage call: the record is a single insert, and no link leaves without it.
 */
@Service
class ProductDownloadService {

    static final Duration LINK_TTL = Duration.ofSeconds(60);

    private final ProductDownloads downloads;
    private final ProductFileStorage storage;
    private final Clock clock;

    ProductDownloadService(ProductDownloads downloads, ProductFileStorage storage, Clock clock) {
        this.downloads = downloads;
        this.storage = storage;
        this.clock = clock;
    }

    /** The link, or empty when the Customer has no active Access to a Product with a file (the API answers 404). */
    Optional<String> issue(UUID customerId, UUID productId) {
        Optional<ProductDownloads.OwnedFile> owned = downloads.findOwned(customerId, productId);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        ProductDownloads.OwnedFile file = owned.get();
        String url = storage.signedDownloadUrl(file.objectKey(), LINK_TTL, DownloadFileName.of(file.title()))
                .orElseThrow(() -> new ProductFileStorageException("Storage object is missing"));
        downloads.record(customerId, productId, file.uploadedAt(), clock.instant());
        return Optional.of(url);
    }
}
