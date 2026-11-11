package br.com.sergio.sapiensia.payment;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/** Shared by the webhook security tests: a stand-in for the real controller and an HTTP client with a chosen source. */
final class AsaasWebhookTestSupport {

    /** The token of src/test/resources/application.properties. */
    static final String TOKEN = "test-only-asaas-webhook-token-0000000000000";

    private AsaasWebhookTestSupport() {}

    /**
     * Stands in for the real controller (Task 3). A router function, not a @RestController: a controller class outside
     * a test class would be picked up by component scanning in every context.
     */
    @TestConfiguration
    static class ProbeConfig {
        @Bean
        RouterFunction<ServerResponse> asaasWebhookProbe() {
            return RouterFunctions.route()
                    .POST("/api/webhooks/asaas", request -> ServerResponse.ok().body("reached"))
                    .build();
        }
    }

    /**
     * Sends a request from its own loopback source address (the server sees it as the connection address); this needs
     * all of 127.0.0.0/8 available, true on Linux CI and Windows.
     */
    static HttpResponse<String> send(int port, String method, String loopback, Map<String, String> headers)
            throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .localAddress(InetAddress.getByName(loopback))
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/webhooks/asaas"))
                .method(
                        method,
                        "POST".equals(method)
                                ? HttpRequest.BodyPublishers.ofString("{}")
                                : HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json");
        headers.forEach(builder::header);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(int port, String loopback, Map<String, String> headers) throws Exception {
        return send(port, "POST", loopback, headers);
    }
}
