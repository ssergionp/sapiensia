package br.com.sergio.sapiensia.product;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;

/** Lets tests of other packages store a file in the (package-private) disk storage and get a local download link. */
public final class LocalDownloadFixtures {

    private LocalDownloadFixtures() {}

    /** Stores the bytes as a Product file and returns the link the disk storage issues for them (60 s). */
    public static String storeAndLink(ApplicationContext context, byte[] bytes, String fileName) throws IOException {
        FileProductFileStorage storage = context.getBean(FileProductFileStorage.class);
        Path source = Files.createTempFile("local-download", ".pdf");
        try {
            Files.write(source, bytes);
            String key = ProductFileKeys.newKey(UUID.randomUUID());
            storage.put(key, source, bytes.length, MediaType.APPLICATION_PDF);
            return storage.signedDownloadUrl(key, java.time.Duration.ofSeconds(60), fileName)
                    .orElseThrow();
        } finally {
            Files.deleteIfExists(source);
        }
    }

    public static boolean hasLocalDownloadController(ApplicationContext context) {
        return context.getBeanNamesForType(LocalDownloadController.class).length > 0;
    }
}
