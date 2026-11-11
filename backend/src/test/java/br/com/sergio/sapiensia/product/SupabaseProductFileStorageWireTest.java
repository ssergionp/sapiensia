package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

/**
 * Points the real adapter, with the real JDK HTTP client, at a local fake server and records what really arrives. The
 * other adapter tests replace the HTTP client, so they never see the request on the wire.
 */
class SupabaseProductFileStorageWireTest {

    private static final String SECRET = "test-secret-not-real";
    private static final String BUCKET = "product-files";

    @TempDir
    Path dir;

    private HttpServer server;
    private final AtomicReference<Recorded> recorded = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
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
            byte[] answer = "{\"Key\":\"x\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
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
                new SupabaseProductFileStorage(RestClient.builder(), baseUrl, SECRET, BUCKET);

        storage.put(key, file, pdf.length);

        Recorded request = recorded.get();
        System.out.println(request.describe());
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/storage/v1/object/" + BUCKET + "/" + key);
        assertThat(request.headers().get("apikey")).containsExactly(SECRET);
        assertThat(request.headers()).doesNotContainKey("Authorization");
        assertThat(request.headers().get("x-upsert")).containsExactly("false");
        assertThat(request.headers().get("Content-Type")).containsExactly("application/pdf");
        assertThat(request.headers().get("Transfer-Encoding")).as("chunked body").isNull();
        assertThat(request.headers().get("Content-Length")).containsExactly(String.valueOf(pdf.length));
        assertThat(request.body()).hasSize(pdf.length);
        assertThat(sha256(request.body())).isEqualTo(sha256(pdf));
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
