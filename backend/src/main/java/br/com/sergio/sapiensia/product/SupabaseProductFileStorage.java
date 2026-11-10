package br.com.sergio.sapiensia.product;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Production: stores the files in a private Supabase Storage bucket. No public URL is ever built or kept. */
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "supabase")
class SupabaseProductFileStorage implements ProductFileStorage {

    private final RestClient restClient;
    private final String objectPath;

    // no RestClient.Builder bean exists in this application, so Spring uses this one and starts from a plain builder
    @Autowired
    SupabaseProductFileStorage(
            @Value("${app.storage.supabase.url}") String url,
            @Value("${app.storage.supabase.secret-key:}") String secretKey,
            @Value("${app.storage.supabase.bucket}") String bucket) {
        this(RestClient.builder(), url, secretKey, bucket);
    }

    // the builder is a test seam
    SupabaseProductFileStorage(RestClient.Builder builder, String url, String secretKey, String bucket) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "APP_STORAGE_SUPABASE_SECRET_KEY is required when app.storage.provider=supabase");
        }
        this.objectPath = "/storage/v1/object/" + bucket + "/";
        // the new secret keys go in the apikey header only, not as a Bearer token
        this.restClient = builder.baseUrl(url)
                .requestFactory(requestFactory())
                .defaultHeader("apikey", secretKey)
                .build();
    }

    // a slow Storage must not hold the caller forever: 2 s to connect and 60 s to read
    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        return requestFactory;
    }

    @Override
    public void put(String key, Path file, long sizeBytes) {
        requireValid(key);
        try {
            restClient
                    .post()
                    .uri(objectPath + key)
                    .header("x-upsert", "false")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(new FileSystemResource(file))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw failure("store", e);
        }
    }

    @Override
    public void delete(String key) {
        requireValid(key);
        try {
            restClient.delete().uri(objectPath + key).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return;
            }
            throw failure("delete", e);
        } catch (RestClientException e) {
            throw failure("delete", e);
        }
    }

    private static void requireValid(String key) {
        if (!ProductFileKeys.isValid(key)) {
            throw new ProductFileStorageException("Invalid storage key");
        }
    }

    // only the status or the class name: a response body, the URL or the secret never reach the message
    private static ProductFileStorageException failure(String action, RestClientException e) {
        String reason = e instanceof RestClientResponseException response
                ? "HTTP " + response.getStatusCode().value()
                : e.getClass().getSimpleName();
        return new ProductFileStorageException("Storage could not " + action + " the file: " + reason);
    }
}
