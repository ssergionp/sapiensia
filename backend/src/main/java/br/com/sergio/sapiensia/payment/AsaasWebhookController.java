package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.alert.AdminAlertType;
import br.com.sergio.sapiensia.alert.AdminAlerts;
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
    private final AdminAlerts alerts;

    AsaasWebhookController(AsaasWebhookService service, AsaasWebhookProperties properties, AdminAlerts alerts) {
        this.service = service;
        this.alerts = alerts;
        this.maxBodyBytes = properties.maxBodyBytes();
    }

    @PostMapping(path = "/api/webhooks/asaas", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            return tooLarge();
        }
        byte[] bytes = request.getInputStream().readNBytes(maxBodyBytes + 1);
        if (bytes.length > maxBodyBytes) {
            return tooLarge();
        }
        try {
            service.receive(new String(bytes, StandardCharsets.UTF_8));
        } catch (InvalidWebhookBodyException e) {
            alerts.recordSafely(AdminAlertType.WEBHOOK_REJECTED, "BODY_INVALID");
            throw e;
        }
        return ResponseEntity.ok().build();
    }

    private ResponseEntity<Void> tooLarge() {
        alerts.recordSafely(AdminAlertType.WEBHOOK_REJECTED, "BODY_TOO_LARGE");
        return ResponseEntity.status(413).build();
    }
}
