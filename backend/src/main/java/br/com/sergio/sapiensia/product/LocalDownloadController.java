package br.com.sergio.sapiensia.product;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The 60-second download link of the disk storage (#33): serves the file while the token has not expired. It does not
 * exist unless the disk provider is on, so it is not a route in production. An unknown or expired token is an empty
 * 404. The token and the file name are never logged.
 */
@RestController
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "file")
class LocalDownloadController {

    private final FileProductFileStorage storage;

    LocalDownloadController(FileProductFileStorage storage) {
        this.storage = storage;
    }

    @GetMapping("/api/local-downloads/{token}")
    ResponseEntity<Resource> get(@PathVariable String token) {
        return storage.localDownload(token)
                .map(download -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(
                                HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment()
                                        .filename(download.fileName())
                                        .build()
                                        .toString())
                        .header("X-Content-Type-Options", "nosniff")
                        .cacheControl(CacheControl.noStore())
                        .<Resource>body(new FileSystemResource(download.file())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
