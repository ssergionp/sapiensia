package br.com.sergio.sapiensia.product;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Local development, CI and tests: keeps the files in a directory. Never for production. */
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "file")
class FileProductFileStorage implements ProductFileStorage {

    private final Path root;

    // bound as a String on purpose: Spring's own String-to-Path conversion treats "../x" as a resource path ("/../x")
    @Autowired
    FileProductFileStorage(@Value("${app.storage.file.dir}") String dir, Environment environment) {
        // a local disk is lost on every deploy and is not private storage: production must use the Supabase bucket
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("app.storage.provider=file is not allowed with the prod profile");
        }
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    FileProductFileStorage(Path root) {
        this.root = root;
    }

    @Override
    public void put(String key, Path file, long sizeBytes) {
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
