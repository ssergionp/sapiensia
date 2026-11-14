package br.com.sergio.sapiensia.product;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Issues the 60-second download link (FR-034) with a daily limit per Customer and Product (SEC-015).
 *
 * <p>Order, all inside one transaction: find the owned file locking the Access row, count today's links, sign, record,
 * return. The lock makes two simultaneous requests of the same Customer and Product pass one at a time, so the second
 * counts only after the first has recorded and committed; other Customers and Products never wait. The price is that
 * the database connection stays busy while the Storage signs (short, and the signing has its own short timeout). A
 * Storage failure or a limit refusal rolls back: no record, the lock and the connection are released.
 */
@Service
class ProductDownloadService {

    static final Duration LINK_TTL = Duration.ofSeconds(60);
    static final int DAILY_LIMIT = 10;
    static final ZoneId DAY_ZONE = ZoneId.of("America/Sao_Paulo");

    private final ProductDownloads downloads;
    private final ProductFileStorage storage;
    private final Clock clock;
    private final TransactionTemplate transaction;

    ProductDownloadService(
            ProductDownloads downloads,
            ProductFileStorage storage,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.downloads = downloads;
        this.storage = storage;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * The link, or empty when the Customer has no active Access to a Product with a file (the API answers 404).
     *
     * @throws DownloadLimitReachedException when the Customer already took {@link #DAILY_LIMIT} links today
     */
    Optional<String> issue(UUID customerId, UUID productId) {
        return transaction.execute(status -> {
            Optional<ProductDownloads.OwnedFile> owned = downloads.findOwned(customerId, productId);
            if (owned.isEmpty()) {
                return Optional.<String>empty();
            }
            Instant now = clock.instant();
            // Clock.withZone is ignored by the test clock, so the day is derived from the instant.
            LocalDate today = now.atZone(DAY_ZONE).toLocalDate();
            Instant dayStart = today.atStartOfDay(DAY_ZONE).toInstant();
            Instant nextDayStart = today.plusDays(1).atStartOfDay(DAY_ZONE).toInstant();
            if (downloads.countIssued(customerId, productId, dayStart, nextDayStart) >= DAILY_LIMIT) {
                throw new DownloadLimitReachedException();
            }
            ProductDownloads.OwnedFile file = owned.get();
            String url = storage.signedDownloadUrl(file.objectKey(), LINK_TTL, DownloadFileName.of(file.title()))
                    .orElseThrow(() -> new ProductFileStorageException("Storage object is missing"));
            downloads.record(customerId, productId, file.uploadedAt(), now);
            return Optional.of(url);
        });
    }
}
