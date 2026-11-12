package br.com.sergio.sapiensia.product;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Production: stores the files in a private Supabase Storage bucket. No public URL is ever built or kept. */
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "supabase")
class SupabaseProductFileStorage implements ProductFileStorage {

    private static final Logger log = LoggerFactory.getLogger(SupabaseProductFileStorage.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    // the new keys and the legacy JWT keys (anon, service_role)
    private static final Pattern SECRET_PATTERN =
            Pattern.compile("sb_(secret|publishable)_[A-Za-z0-9_-]*|eyJ[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+){1,2}");
    // anything that looks like a file name (no space, a dot, then 1 to 6 letters or digits): on doubt, hide it
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("\\S*\\.[A-Za-z0-9]{1,6}\\S*");
    private static final String REDACTED = "<redacted>";
    private static final int MAX_FIELD = 200;

    private final RestClient restClient;
    private final String objectPath;
    private final String secretKey;
    private final String baseUrl;
    private final String host;

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
        this.secretKey = secretKey;
        this.baseUrl = url;
        String parsedHost = URI.create(url).getHost();
        this.host = parsedHost == null ? "" : parsedHost;
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
            throw failure("store", key, e);
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
            throw failure("delete", key, e);
        } catch (RestClientException e) {
            throw failure("delete", key, e);
        }
    }

    private static void requireValid(String key) {
        if (!ProductFileKeys.isValid(key)) {
            throw new ProductFileStorageException("Invalid storage key");
        }
    }

    // only the status or the class name: a response body, the URL or the secret never reach the message
    private ProductFileStorageException failure(String action, String key, RestClientException e) {
        String reason = e instanceof RestClientResponseException response
                ? "HTTP " + response.getStatusCode().value()
                : e.getClass().getSimpleName();
        String message = "Storage could not " + action + " the file: " + reason;
        if (e instanceof RestClientResponseException response) {
            log.warn("{}{}", message, safeDetails(response.getResponseBodyAsString(), key));
        }
        return new ProductFileStorageException(message);
    }

    // Supabase Storage often answers 400 and puts the real status in the JSON body: log only statusCode, error and
    // message, with the secret, the project URL and the object path taken out
    private String safeDetails(String body, String key) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            return "";
        }
        if (root == null || !root.isObject()) {
            return "";
        }
        return " (statusCode=" + field(root, "statusCode", key) + ", error=" + field(root, "error", key) + ", message="
                + field(root, "message", key) + ")";
    }

    private String field(JsonNode root, String name, String key) {
        JsonNode node = root.get(name);
        if (node == null || !node.isValueNode()) {
            return "";
        }
        String text = node.asString();
        for (String hidden : List.of(secretKey, baseUrl, host, objectPath, key)) {
            if (!hidden.isEmpty()) {
                text = text.replace(hidden, REDACTED);
            }
        }
        text = SECRET_PATTERN.matcher(text).replaceAll(REDACTED).replaceAll("\\p{Cntrl}", " ");
        text = FILE_NAME_PATTERN.matcher(text).replaceAll(REDACTED);
        return text.length() > MAX_FIELD ? text.substring(0, MAX_FIELD) : text;
    }
}
