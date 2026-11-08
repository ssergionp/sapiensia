package br.com.sergio.sapiensia.audit;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorrelationIdFilterTest {

    private static final String HEADER = "X-Correlation-Id";

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("SEC-065: shouldAnswer200401And403WithACorrelationId")
    void shouldAnswer200401And403WithACorrelationId() throws Exception {
        HttpResponse<String> ok = send("GET", "/api/system/health", null);
        HttpResponse<String> unauthorized = send("GET", "/api/anything", null);
        HttpResponse<String> forbidden = send("POST", "/api/system/health", null);

        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        for (HttpResponse<String> response : new HttpResponse[] {ok, unauthorized, forbidden}) {
            assertThat(UUID.fromString(response.headers().firstValue(HEADER).orElseThrow()))
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("SEC-065: shouldUseANewCorrelationIdOnEachRequest")
    void shouldUseANewCorrelationIdOnEachRequest() throws Exception {
        String first = send("GET", "/api/system/health", null)
                .headers()
                .firstValue(HEADER)
                .orElseThrow();
        String second = send("GET", "/api/system/health", null)
                .headers()
                .firstValue(HEADER)
                .orElseThrow();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("SEC-065: shouldIgnoreACorrelationIdSentByTheClient")
    void shouldIgnoreACorrelationIdSentByTheClient() throws Exception {
        String sent = "someone@example.com";

        String received = send("GET", "/api/system/health", sent)
                .headers()
                .firstValue(HEADER)
                .orElseThrow();

        assertThat(received).isNotEqualTo(sent);
        assertThat(UUID.fromString(received)).isNotNull();
    }

    @Test
    @DisplayName("SEC-065: shouldLeaveTheMdcCleanAfterTheRequest")
    void shouldLeaveTheMdcCleanAfterTheRequest() throws Exception {
        send("GET", "/api/system/health", null);

        assertThat(MDC.get("correlationId")).isNull();
    }

    private HttpResponse<String> send(String method, String path, String correlationHeader) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (correlationHeader != null) {
            request.header(HEADER, correlationHeader);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
