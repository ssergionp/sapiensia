package br.com.sergio.sapiensia.email;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Sends through the Resend HTTP API. Any non-2xx answer is a failure. */
@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")
class ResendEmailSender implements EmailSender {

    private final RestClient restClient;
    private final String from;

    // no RestClient.Builder bean exists in this application, so Spring uses this one and starts from a plain builder
    @Autowired
    ResendEmailSender(@Value("${app.email.resend.api-key:}") String apiKey, @Value("${app.email.from}") String from) {
        this(RestClient.builder(), apiKey, from);
    }

    // the builder is a test seam
    ResendEmailSender(
            RestClient.Builder builder,
            @Value("${app.email.resend.api-key:}") String apiKey,
            @Value("${app.email.from}") String from) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("RESEND_API_KEY is required when app.email.provider=resend");
        }
        this.from = from;
        this.restClient = builder.baseUrl("https://api.resend.com")
                .requestFactory(requestFactory())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    // a slow Resend must not hold the caller: 2 s to connect and 5 s to read
    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return requestFactory;
    }

    @Override
    public void send(EmailMessage message) {
        Map<String, Object> body = Map.of(
                "from", from,
                "to", List.of(message.to()),
                "subject", message.subject(),
                "text", message.text());
        try {
            RestClient.RequestBodySpec request =
                    restClient.post().uri("/emails").contentType(MediaType.APPLICATION_JSON);
            if (message.idempotencyKey() != null) {
                request.header("Idempotency-Key", message.idempotencyKey());
            }
            request.body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            // only the status: the body may echo the recipient
            throw new EmailDeliveryException(
                    "Resend rejected the e-mail with HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            // the cause is dropped on purpose: its message may carry the recipient
            throw new EmailDeliveryException(
                    "Resend could not be reached: " + e.getClass().getSimpleName());
        }
    }
}
