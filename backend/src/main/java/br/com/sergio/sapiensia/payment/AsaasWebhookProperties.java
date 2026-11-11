package br.com.sergio.sapiensia.payment;

import br.com.sergio.sapiensia.customer.ClientIpResolver;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Asaas webhook settings (app.asaas.webhook.*): the shared secret, the official source IPs and the body size cap. */
@Validated
@ConfigurationProperties("app.asaas.webhook")
public record AsaasWebhookProperties(
        @NotBlank @Size(min = 32, max = 255) @Pattern(regexp = "\\S+")
        String token,

        @NotEmpty List<String> allowedIps,
        @Positive int maxBodyBytes) {

    public AsaasWebhookProperties {
        if (allowedIps != null) {
            for (String ip : allowedIps) {
                if (ip == null || ClientIpResolver.canonicalLiteralIp(ip.trim()) == null) {
                    // the offending value is not echoed in the message
                    throw new IllegalArgumentException("Invalid app.asaas.webhook.allowed-ips");
                }
            }
        }
    }

    /** The allowed addresses in the canonical form that ClientIpResolver produces. */
    public Set<String> canonicalAllowedIps() {
        return allowedIps.stream()
                .map(ip -> ClientIpResolver.canonicalLiteralIp(ip.trim()))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String toString() {
        return "AsaasWebhookProperties[token=[redacted], allowedIps=" + allowedIps + ", maxBodyBytes=" + maxBodyBytes
                + "]";
    }
}
