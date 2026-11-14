package br.com.sergio.sapiensia.product;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Local development, CI and tests: keeps the files in a directory. Never for production. */
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "file")
class FileProductFileStorage implements ProductFileStorage {

    private final Path root;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Link> links = new ConcurrentHashMap<>();

    /** A file the local download route serves: the file on disk and the name it is saved as. */
    record LocalDownload(Path file, String fileName) {}

    private record Link(Path file, String fileName, Instant expiresAt) {}

    // bound as a String on purpose: Spring's own String-to-Path conversion treats "../x" as a resource path ("/../x")
    @Autowired
    FileProductFileStorage(@Value("${app.storage.file.dir}") String dir, Environment environment, Clock clock) {
        // a local disk is lost on every deploy and is not private storage: production must use the Supabase bucket
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("app.storage.provider=file is not allowed with the prod profile");
        }
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.clock = clock;
    }

    FileProductFileStorage(Path root, Clock clock) {
        this.root = root;
        this.clock = clock;
    }

    @Override
    public void put(String key, Path file, long sizeBytes, MediaType type) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            // no REPLACE_EXISTING: an existing object makes this fail, so nothing is ever overwritten
            Files.copy(file, target);
        } catch (IOException e) {
            // the cause is dropped on purpose: its message carries file paths
            throw new ProductFileStorageException(
                    "Could not store the file: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try (var in = Files.newInputStream(target)) {
            byte[] bytes = in.readNBytes((int) CoverImageValidator.MAX_BYTES + 1);
            if (bytes.length > CoverImageValidator.MAX_BYTES) {
                throw new ProductFileStorageException("Stored object is too large");
            }
            return Optional.of(bytes);
        } catch (IOException e) {
            throw new ProductFileStorageException(
                    "Could not read the file: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public Optional<String> signedDownloadUrl(String key, Duration ttl, String fileName) {
        Path target = resolve(key);
        if (ProductFileKeys.isCover(key)) {
            throw new ProductFileStorageException("Invalid storage key");
        }
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        links.values().removeIf(link -> !now.isBefore(link.expiresAt()));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        links.put(token, new Link(target, fileName, now.plus(ttl)));
        return Optional.of("/api/local-downloads/" + token);
    }

    /** The file behind a token that is still valid (now < issue + ttl); empty for an unknown or expired one. */
    Optional<LocalDownload> localDownload(String token) {
        Link link = links.get(token);
        if (link == null || !clock.instant().isBefore(link.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(new LocalDownload(link.file(), link.fileName()));
    }

    /** Number of links still in the map, expired ones included until the next issue (for tests). */
    int pendingLinks() {
        return links.size();
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new ProductFileStorageException(
                    "Could not delete the file: " + e.getClass().getSimpleName());
        }
    }

    private Path resolve(String key) {
        if (!ProductFileKeys.isValid(key)) {
            throw new ProductFileStorageException("Invalid storage key");
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new ProductFileStorageException("Invalid storage key");
        }
        return target;
    }
}
