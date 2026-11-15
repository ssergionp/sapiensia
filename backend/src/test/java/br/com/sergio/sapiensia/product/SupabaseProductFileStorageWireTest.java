package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Points the real adapter, with the real JDK HTTP client, at a local fake server and records what really arrives. The
 * other adapter tests replace the HTTP client, so they never see the request on the wire.
 */
@ExtendWith(OutputCaptureExtension.class)
class SupabaseProductFileStorageWireTest {

    private static final MediaType PDF = MediaType.APPLICATION_PDF;

    private static final String SECRET = "test-secret-not-real";
    private static final String BUCKET = "product-files";
    private static final String COVER_BUCKET = "product-covers";
    private static final String TOKEN = "tk" + UUID.randomUUID().toString().replace("-", "");
    private static final List<String> LOGGERS = List.of(
            "org.springframework.web",
            "org.springframework.security",
            "org.springframework.web.client",
            "org.springframework.http");

    private final Map<String, LogLevel> savedLevels = new LinkedHashMap<>();
    private LoggingSystem loggingSystem;

    @TempDir
    Path dir;

    private HttpServer server;
    private final AtomicReference<Recorded> recorded = new AtomicReference<>();
    private volatile long signDelayMillis;
    private volatile long putDelayMillis;

    @BeforeEach
    void startServer() throws IOException {
        loggingSystem = LoggingSystem.get(getClass().getClassLoader());
        for (String name : LOGGERS) {
            // the configuration is null when no logger of that name exists yet (this class run alone)
            LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(name);
            savedLevels.put(name, configuration == null ? null : configuration.getConfiguredLevel());
        }
        loggingSystem.setLogLevel("org.springframework.web", LogLevel.DEBUG);
        loggingSystem.setLogLevel("org.springframework.security", LogLevel.TRACE);
        loggingSystem.setLogLevel("org.springframework.web.client", LogLevel.DEBUG);
        loggingSystem.setLogLevel("org.springframework.http", LogLevel.DEBUG);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(exchange.getRequestHeaders());
            recorded.set(new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getProtocol(),
                    headers,
                    body));
            boolean read = "GET".equals(exchange.getRequestMethod());
            boolean sign = exchange.getRequestURI().getRawPath().contains("/object/sign/");
            sleep(sign ? signDelayMillis : "POST".equals(exchange.getRequestMethod()) ? putDelayMillis : 0);
            byte[] answer = sign
                    ? ("{\"signedURL\":\""
                                    + exchange.getRequestURI().getRawPath().replace("/storage/v1", "")
                                    + "?token=" + TOKEN + "\"}")
                            .getBytes(StandardCharsets.UTF_8)
                    : read ? new byte[] {1, 2, 3} : "{\"Key\":\"x\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", read && !sign ? "image/png" : "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        // a handler that sleeps must not block the other requests
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterEach
    void stopServer() {
        savedLevels.forEach(loggingSystem::setLogLevel); // null restores inheritance
        savedLevels.clear();
        server.stop(0);
        ((ExecutorService) server.getExecutor()).shutdownNow();
    }

    @Test
    @DisplayName("SEC-047: o adaptador envia ao Storage a mesma requisição que o curl")
    void shouldSendTheSameRequestAsCurl() throws IOException {
        byte[] pdf = new byte[1024];
        byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, pdf, 0, header.length);
        for (int i = header.length; i < pdf.length; i++) {
            pdf[i] = (byte) i;
        }
        Path file = Files.write(dir.resolve("f.pdf"), pdf);
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        SupabaseProductFileStorage storage =
                new SupabaseProductFileStorage(RestClient.builder(), baseUrl, SECRET, BUCKET, COVER_BUCKET);

        storage.put(key, file, pdf.length, PDF);

        Recorded request = recorded.get();
        System.out.println(request.describe());
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/storage/v1/object/" + BUCKET + "/" + key);
        assertThat(request.headers().get("apikey")).containsExactly(SECRET);
        assertThat(request.headers()).doesNotContainKey("Authorization");
        assertThat(request.headers().get("x-upsert")).containsExactly("false");
        assertThat(request.headers().get("Content-Type")).containsExactly("application/pdf");
        assertThat(request.headers().get("Transfer-Encoding"))
                .as("chunked body")
                .isNull();
        assertThat(request.headers().get("Content-Length")).containsExactly(String.valueOf(pdf.length));
        assertThat(request.body()).hasSize(pdf.length);
        assertThat(sha256(request.body())).isEqualTo(sha256(pdf));
    }

    @Test
    @DisplayName("#21: capa vai ao bucket de capas com o Content-Type da imagem e volta pela rota autenticada")
    void shouldPutAndGetACoverOnTheWire() throws IOException {
        Path file = Files.write(dir.resolve("c.png"), new byte[] {1, 2, 3});
        String key = ProductFileKeys.newCoverKey(UUID.randomUUID(), CoverFormat.PNG);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        SupabaseProductFileStorage storage =
                new SupabaseProductFileStorage(RestClient.builder(), baseUrl, SECRET, BUCKET, COVER_BUCKET);

        storage.put(key, file, 3, org.springframework.http.MediaType.IMAGE_PNG);

        Recorded put = recorded.get();
        assertThat(put.method()).isEqualTo("POST");
        assertThat(put.path()).isEqualTo("/storage/v1/object/" + COVER_BUCKET + "/" + key);
        assertThat(put.headers().get("Content-Type")).containsExactly("image/png");
        assertThat(put.headers().get("Content-Length")).containsExactly("3");

        assertThat(storage.get(key)).contains(new byte[] {1, 2, 3});
        Recorded get = recorded.get();
        assertThat(get.method()).isEqualTo("GET");
        assertThat(get.path()).isEqualTo("/storage/v1/object/authenticated/" + COVER_BUCKET + "/" + key);
        assertThat(get.headers().get("apikey")).containsExactly(SECRET);
    }

    @Test
    @DisplayName("FR-034: a assinatura vai ao Storage como POST com apikey e corpo expiresIn 60")
    void shouldSendTheSignRequestOnTheWire() {
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        SupabaseProductFileStorage storage =
                new SupabaseProductFileStorage(RestClient.builder(), baseUrl, SECRET, BUCKET, COVER_BUCKET);

        assertThat(storage.signedDownloadUrl(key, Duration.ofSeconds(60), "guia-de-contratos.pdf"))
                .contains(baseUrl + "/storage/v1/object/sign/" + BUCKET + "/" + key + "?token=" + TOKEN
                        + "&download=guia-de-contratos.pdf");

        Recorded request = recorded.get();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/storage/v1/object/sign/" + BUCKET + "/" + key);
        assertThat(request.headers().get("apikey")).containsExactly(SECRET);
        assertThat(request.headers().get("Content-Type").get(0)).startsWith("application/json");
        assertThat(new String(request.body(), StandardCharsets.UTF_8)).isEqualTo("{\"expiresIn\":60}");
    }

    @Test
    @DisplayName("FR-034: a assinatura devolve a URL e os logs não trazem token, caminho, chave, nome nem segredo")
    void shouldKeepTheSignedUrlOutOfTheLogs(CapturedOutput output) {
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        String fileName = "guia-de-contratos-sigilo.pdf";
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        SupabaseProductFileStorage storage =
                new SupabaseProductFileStorage(RestClient.builder(), baseUrl, SECRET, BUCKET, COVER_BUCKET);
        int start = output.getAll().length();

        java.util.Optional<String> url = storage.signedDownloadUrl(key, Duration.ofSeconds(60), fileName);

        assertThat(url).isPresent();
        assertThat(url.get()).contains(TOKEN);
        assertThat(output.getAll().substring(start))
                .as("the capture is live: the client really logs the request body at DEBUG")
                .contains("expiresIn")
                .doesNotContain(TOKEN)
                .doesNotContain("/object/sign/")
                .doesNotContain(key)
                .doesNotContain(fileName)
                .doesNotContain(SECRET);
    }

    @Test
    @DisplayName("SEC-015: a assinatura desiste em 5 s quando o Storage demora")
    void shouldGiveUpSigningWhenStorageIsSlow() {
        signDelayMillis = 8000;
        String key = ProductFileKeys.newKey(UUID.randomUUID());
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        SupabaseProductFileStorage storage =
                new SupabaseProductFileStorage(RestClient.builder(), baseUrl, SECRET, BUCKET, COVER_BUCKET);
        long start = System.nanoTime();

        assertThatThrownBy(() -> storage.signedDownloadUrl(key, Duration.ofSeconds(60), "guia.pdf"))
                .isInstanceOf(ProductFileStorageException.class)
                .message()
                .doesNotContain(baseUrl)
                .doesNotContain("127.0.0.1")
                .doesNotContain(key)
                .doesNotContain("token");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("O envio de arquivo continua com o tempo limite longo")
    void shouldKeepTheLongTimeoutForUploads() throws IOException {
        // 6 s is above the 5 s signing limit, so a successful put proves uploads do not use it
        putDelayMillis = 6000;
        Path file = Files.write(dir.resolve("slow.png"), new byte[] {1, 2, 3});
        String key = ProductFileKeys.newCoverKey(UUID.randomUUID(), CoverFormat.PNG);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        SupabaseProductFileStorage storage =
                new SupabaseProductFileStorage(RestClient.builder(), baseUrl, SECRET, BUCKET, COVER_BUCKET);

        storage.put(key, file, 3, MediaType.IMAGE_PNG);

        assertThat(recorded.get().method()).isEqualTo("POST");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Recorded(
            String method, String path, String protocol, Map<String, List<String>> headers, byte[] body) {

        // the apikey value is never printed
        String describe() {
            StringBuilder text = new StringBuilder(method + " <path> " + protocol + "\n");
            headers.forEach((name, values) -> text.append(name)
                    .append(": ")
                    .append(name.equalsIgnoreCase("apikey") ? "<present>" : String.join(", ", values))
                    .append('\n'));
            return text.append("body bytes: ").append(body.length).toString();
        }
    }
}
