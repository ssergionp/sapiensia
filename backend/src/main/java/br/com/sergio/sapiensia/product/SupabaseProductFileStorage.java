package br.com.sergio.sapiensia.product;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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

/**
 * Production: stores the objects in private Supabase Storage buckets, chosen by the key prefix: {@code products/} in
 * the files bucket and {@code covers/} in the covers bucket. No public URL is ever built or kept.
 *
 * <p>Reads use the authenticated download route {@code GET /storage/v1/object/authenticated/{bucket}/{key}}, which the
 * secret key may call on a private bucket. Route: supabase/storage, src/http/routes/object/getObject.ts
 * (https://supabase.com/docs/reference/javascript/storage-from-download is the client reference). Storage answers a
 * missing object with HTTP 404, or with HTTP 400 and {@code "statusCode":"404"} in the JSON body: both mean "absent".
 */
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
    private static final String SIGNED_PREFIX = "/object/sign/";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("token=\\S*");
    private static final String REDACTED = "<redacted>";
    private static final int MAX_FIELD = 200;
    // an error body is only read to log three short fields
    private static final int MAX_ERROR_BODY = 8192;

    private final RestClient restClient;
    private final String filesBucket;
    private final String coverBucket;
    private final String secretKey;
    private final String baseUrl;
    private final String host;

    // no RestClient.Builder bean exists in this application, so Spring uses this one and starts from a plain builder
    @Autowired
    SupabaseProductFileStorage(
            @Value("${app.storage.supabase.url}") String url,
            @Value("${app.storage.supabase.secret-key:}") String secretKey,
            @Value("${app.storage.supabase.bucket}") String bucket,
            @Value("${app.storage.supabase.cover-bucket}") String coverBucket) {
        this(RestClient.builder(), url, secretKey, bucket, coverBucket);
    }

    // the builder is a test seam
    SupabaseProductFileStorage(
            RestClient.Builder builder, String url, String secretKey, String bucket, String coverBucket) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "APP_STORAGE_SUPABASE_SECRET_KEY is required when app.storage.provider=supabase");
        }
        this.filesBucket = bucket;
        this.coverBucket = coverBucket;
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

    private String bucketOf(String key) {
        return ProductFileKeys.isCover(key) ? coverBucket : filesBucket;
    }

    private String objectPath(String key) {
        return "/storage/v1/object/" + bucketOf(key) + "/" + key;
    }

    private String signPath(String key) {
        return "/storage/v1/object/sign/" + filesBucket + "/" + key;
    }

    private String authenticatedPath(String key) {
        return "/storage/v1/object/authenticated/" + bucketOf(key) + "/" + key;
    }

    @Override
    public void put(String key, Path file, long sizeBytes, MediaType type) {
        requireValid(key);
        try {
            restClient
                    .post()
                    .uri(objectPath(key))
                    .header("x-upsert", "false")
                    .contentType(type)
                    .body(new FileSystemResource(file))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw failure("store", key, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        requireValid(key);
        try {
            return restClient.get().uri(authenticatedPath(key)).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (response.getStatusCode().is2xxSuccessful()) {
                    byte[] bytes = response.getBody().readNBytes((int) CoverImageValidator.MAX_BYTES + 1);
                    if (bytes.length > CoverImageValidator.MAX_BYTES) {
                        throw new ProductFileStorageException("Storage object is too large");
                    }
                    return Optional.of(bytes);
                }
                String body = new String(response.getBody().readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8);
                if (status == 404 || (status == 400 && bodyStatusCode(body).equals("404"))) {
                    return Optional.<byte[]>empty();
                }
                log.warn("Storage could not read the file: HTTP {}{}", status, safeDetails(body, key));
                throw new ProductFileStorageException("Storage could not read the file: HTTP " + status);
            });
        } catch (RestClientException e) {
            throw failure("read", key, e);
        }
    }

    @Override
    public Optional<String> signedDownloadUrl(String key, Duration ttl, String fileName) {
        requireValid(key);
        if (ProductFileKeys.isCover(key)) {
            throw new ProductFileStorageException("Invalid storage key");
        }
        try {
            JsonNode answer = restClient
                    .post()
                    .uri(signPath(key))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"expiresIn\":" + ttl.toSeconds() + "}")
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode signed = answer == null ? null : answer.get("signedURL");
            // never echo the answer: it carries the token
            if (signed == null || !signed.isString() || !signed.asString().startsWith(SIGNED_PREFIX)) {
                throw new ProductFileStorageException("Storage could not sign the file: unexpected response");
            }
            return Optional.of(baseUrl + "/storage/v1" + signed.asString() + "&download="
                    + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            if (e.getStatusCode().value() == 404
                    || (e.getStatusCode().value() == 400 && bodyStatusCode(body).equals("404"))) {
                return Optional.empty();
            }
            throw failure("sign", key, e);
        } catch (RestClientException e) {
            throw failure("sign", key, e);
        }
    }

    @Override
    public void delete(String key) {
        requireValid(key);
        try {
            restClient.delete().uri(objectPath(key)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return;
            }
            throw failure("delete", key, e);
        } catch (RestClientException e) {
            throw failure("delete", key, e);
        }
    }

    private static String bodyStatusCode(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode code = root == null ? null : root.get("statusCode");
            return code != null && code.isValueNode() ? code.asString() : "";
        } catch (JacksonException e) {
            return "";
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
        // the object paths of both buckets, plain and authenticated, then the key itself
        for (String hidden : List.of(
                secretKey,
                baseUrl,
                host,
                "/storage/v1/object/authenticated/" + filesBucket + "/",
                "/storage/v1/object/authenticated/" + coverBucket + "/",
                "/storage/v1/object/sign/" + filesBucket + "/",
                SIGNED_PREFIX + filesBucket + "/",
                "/storage/v1/object/" + filesBucket + "/",
                "/storage/v1/object/" + coverBucket + "/",
                key)) {
            if (!hidden.isEmpty()) {
                text = text.replace(hidden, REDACTED);
            }
        }
        text = TOKEN_PATTERN.matcher(text).replaceAll(REDACTED);
        text = SECRET_PATTERN.matcher(text).replaceAll(REDACTED).replaceAll("\\p{Cntrl}", " ");
        text = FILE_NAME_PATTERN.matcher(text).replaceAll(REDACTED);
        return text.length() > MAX_FIELD ? text.substring(0, MAX_FIELD) : text;
    }
}
