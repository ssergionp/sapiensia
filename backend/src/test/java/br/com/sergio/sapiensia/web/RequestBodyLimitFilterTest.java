package br.com.sergio.sapiensia.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * SEC-015 and SEC-013: a JSON body above 64 KiB is refused with 413, whether it declares its size or arrives chunked,
 * and multipart uploads and the webhook keep their own limits. A real HTTP client, so Tomcat sees what a client sends.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=request-body-limit", "app.rate-limit.ip-capacity-floor=10000"})
class RequestBodyLimitFilterTest {

    private static final int OVER_THE_LIMIT = 100 * 1024;

    @LocalServerPort
    int port;

    private final CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(jar).build();

    @BeforeEach
    void visit() throws Exception {
        // the SPA page hands out the XSRF-TOKEN cookie that every unsafe request must echo
        http.send(request("/cadastro").build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private String xsrf() {
        return jar.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue)
                .reduce((first, last) -> last)
                .orElseThrow();
    }

    private static String bigJson(int bytes) {
        return "{\"email\":\"" + "a".repeat(bytes) + "\"}";
    }

    private HttpResponse<String> postJson(HttpRequest.BodyPublisher body) throws Exception {
        return http.send(
                request("/api/customers")
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", xsrf())
                        .POST(body)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void assertPayloadTooLarge(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(413);
        // the body was not read: the connection must not be reused
        assertThat(response.headers().firstValue("Connection").orElse("")).isEqualToIgnoringCase("close");
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("json");
        assertThat((String) JsonPath.read(response.body(), "$.title")).isEqualTo("Payload too large");
        assertThat((Integer) JsonPath.read(response.body(), "$.status")).isEqualTo(413);
        assertThat((String) JsonPath.read(response.body(), "$.code")).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    @DisplayName("SEC-015, SEC-013: shouldRefuseAJsonBodyAbove64KiBThatDeclaresItsSize")
    void shouldRefuseAJsonBodyAbove64KiBThatDeclaresItsSize() throws Exception {
        assertPayloadTooLarge(postJson(HttpRequest.BodyPublishers.ofString(bigJson(OVER_THE_LIMIT))));
    }

    @Test
    @DisplayName("SEC-015, SEC-013: shouldRefuseAChunkedJsonBodyAbove64KiBWithoutContentLength")
    void shouldRefuseAChunkedJsonBodyAbove64KiBWithoutContentLength() throws Exception {
        byte[] bytes = bigJson(OVER_THE_LIMIT).getBytes(StandardCharsets.UTF_8);
        // no known length: the client sends Transfer-Encoding: chunked
        HttpRequest.BodyPublisher chunked = HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)));
        assertPayloadTooLarge(postJson(chunked));
    }

    @Test
    @DisplayName("SEC-015: shouldStillReadAJsonBodyBelowTheLimit")
    void shouldStillReadAJsonBodyBelowTheLimit() throws Exception {
        HttpResponse<String> response = postJson(HttpRequest.BodyPublishers.ofString(bigJson(1024)));
        // the e-mail is invalid, so the route answers its own validation error, not a size error
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.title")).isEqualTo("Invalid request");
    }

    private static String multipartBody(String boundary) {
        return "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n" + "x".repeat(OVER_THE_LIMIT) + "\r\n--" + boundary + "--\r\n";
    }

    private HttpResponse<String> postMultipart(String path, HttpRequest.BodyPublisher body, String boundary)
            throws Exception {
        return http.send(
                request(path)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("X-XSRF-TOKEN", xsrf())
                        .POST(body)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("SEC-015: shouldNotApplyTheJsonLimitToAMultipartUpload")
    void shouldNotApplyTheJsonLimitToAMultipartUpload() throws Exception {
        String boundary = "----limit" + UUID.randomUUID();
        for (String route : new String[] {"/file", "/cover"}) {
            HttpResponse<String> response = postMultipart(
                    "/api/admin/products/" + UUID.randomUUID() + route,
                    HttpRequest.BodyPublishers.ofString(multipartBody(boundary)),
                    boundary);
            // anonymous: the security chain answers (401), which proves the size filter did not stop it first
            assertThat(response.statusCode()).isNotEqualTo(413).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("SEC-015: corpo multipart grande numa rota que não é de upload recebe 413")
    void shouldRefuseABigMultipartBodyOnARouteThatIsNotAnUpload() throws Exception {
        String boundary = "----limit" + UUID.randomUUID();
        byte[] bytes = multipartBody(boundary).getBytes(StandardCharsets.UTF_8);

        assertPayloadTooLarge(postMultipart("/api/customers", HttpRequest.BodyPublishers.ofByteArray(bytes), boundary));

        HttpRequest.BodyPublisher chunked = HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)));
        HttpResponse<String> response = postMultipart("/api/customers", chunked, boundary);
        // chunked: the JSON route never reads a multipart body (415 before any read), or the counting stream stops it
        // (413); what matters is that the 100 KiB body is never processed as a sign-up
        assertThat(response.statusCode()).isIn(400, 413, 415);
    }

    @Test
    @DisplayName("SEC-015: corpo de formulário grande e em pedaços não passa pelo Tomcat")
    void shouldRefuseAChunkedFormBodyAbove64KiBBeforeTheCsrfFallbackParsesIt() throws Exception {
        byte[] bytes = ("_csrf=" + "a".repeat(OVER_THE_LIMIT)).getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher chunked = HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)));
        HttpResponse<String> response = http.send(
                request("/api/customers")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(chunked)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        // no XSRF header: the CSRF filter falls back to getParameter, which makes Tomcat read the form. With
        // max-http-form-post-size at 64 KB Tomcat refuses it (413 or 400) instead of parsing 100 KiB (which would
        // end in 403 for the missing token)
        assertThat(response.statusCode()).isIn(400, 413);
    }

    @Test
    @DisplayName("SEC-015: shouldLeaveTheWebhookToItsOwnLimit")
    void shouldLeaveTheWebhookToItsOwnLimit() throws Exception {
        HttpResponse<String> response = http.send(
                request("/api/webhooks/asaas")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(bigJson(OVER_THE_LIMIT)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        // the webhook guard (token and source IP) answers first; there is no PAYLOAD_TOO_LARGE problem body from the
        // filter
        assertThat(response.body()).doesNotContain("PAYLOAD_TOO_LARGE");
    }

    @Test
    @DisplayName("SEC-015: shouldAcceptHeadersUpTo16KiBAndRefuseLargerOnes")
    void shouldAcceptHeadersUpTo16KiBAndRefuseLargerOnes() throws Exception {
        // Tomcat's own default is 8 KiB: 12 KiB passing proves the explicit 16 KiB setting is in force
        HttpResponse<String> within = http.send(
                request("/api/products")
                        .header("X-Padding", "a".repeat(12 * 1024))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(within.statusCode()).isEqualTo(200);
        HttpResponse<String> above = http.send(
                request("/api/products")
                        .header("X-Padding", "a".repeat(20 * 1024))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(above.statusCode()).isIn(400, 431);
    }
}
