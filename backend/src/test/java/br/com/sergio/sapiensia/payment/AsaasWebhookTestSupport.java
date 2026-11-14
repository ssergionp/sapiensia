package br.com.sergio.sapiensia.payment;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

/** Shared by the webhook tests: event builders and an HTTP client with a chosen source address. */
final class AsaasWebhookTestSupport {

    /** The token of src/test/resources/application.properties. */
    static final String TOKEN = "test-only-asaas-webhook-token-0000000000000";

    private AsaasWebhookTestSupport() {}

    /** A minimal event as in the provider's documentation. */
    static String event(String id, String type) {
        return event(id, type, "59.9");
    }

    static String event(String id, String type, String value) {
        return """
                {"id": "%s", "event": "%s", "dateCreated": "2026-11-11 10:00:00",
                 "payment": {"object": "payment", "id": "pay_000", "customer": "cus_000", "value": %s,
                             "billingType": "PIX", "status": "RECEIVED"}}""".formatted(id, type, value);
    }

    /** A payment event as in the provider's documentation, for the charge with this id. */
    static String paymentEvent(String id, String type, String chargeId) {
        return """
                {"id": "%s", "event": "%s", "dateCreated": "2026-11-11 10:00:00",
                 "payment": {"object": "payment", "id": "%s", "customer": "cus_000", "value": 29.9,
                             "billingType": "PIX", "status": "RECEIVED"}}""".formatted(id, type, chargeId);
    }

    /** A unique event id, so tests never collide in the shared database. */
    static String uniqueId() {
        return "evt_" + UUID.randomUUID();
    }

    /**
     * Sends a request from its own loopback source address (the server sees it as the connection address); this needs
     * all of 127.0.0.0/8 available, true on Linux CI and Windows.
     */
    static HttpResponse<String> send(int port, String method, String loopback, Map<String, String> headers)
            throws Exception {
        return send(port, method, loopback, headers, "{}", "application/json");
    }

    static HttpResponse<String> send(
            int port, String method, String loopback, Map<String, String> headers, String body, String contentType)
            throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .localAddress(InetAddress.getByName(loopback))
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/webhooks/asaas"))
                .method(
                        method,
                        "POST".equals(method)
                                ? HttpRequest.BodyPublishers.ofString(body)
                                : HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", contentType);
        headers.forEach(builder::header);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A POST with a valid minimal event and a unique id. */
    static HttpResponse<String> post(int port, String loopback, Map<String, String> headers) throws Exception {
        return post(port, loopback, headers, event(uniqueId(), "PAYMENT_RECEIVED"), "application/json");
    }

    static HttpResponse<String> post(
            int port, String loopback, Map<String, String> headers, String body, String contentType) throws Exception {
        return send(port, "POST", loopback, headers, body, contentType);
    }
}
