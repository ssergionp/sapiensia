package br.com.sergio.sapiensia.payment;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Receives Asaas webhook events; access is guarded in SecurityConfig. Answers 200 without a body. */
@RestController
class AsaasWebhookController {

    private final AsaasWebhookService service;
    private final int maxBodyBytes;

    AsaasWebhookController(AsaasWebhookService service, AsaasWebhookProperties properties) {
        this.service = service;
        this.maxBodyBytes = properties.maxBodyBytes();
    }

    @PostMapping(path = "/api/webhooks/asaas", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            return ResponseEntity.status(413).build();
        }
        byte[] bytes = request.getInputStream().readNBytes(maxBodyBytes + 1);
        if (bytes.length > maxBodyBytes) {
            return ResponseEntity.status(413).build();
        }
        service.receive(new String(bytes, StandardCharsets.UTF_8));
        return ResponseEntity.ok().build();
    }
}
