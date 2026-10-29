package br.com.sergio.sapiensia.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.password.HaveIBeenPwnedRestApiPasswordChecker;
import org.springframework.web.client.RestClient;

/**
 * Password hashing and leaked-password check.
 *
 * <ul>
 *   <li>Argon2id with the OWASP minimum parameters (19 MiB of memory, 2 iterations, 1 lane).
 *   <li>Have I Been Pwned range API (k-anonymity: only 5 characters of the SHA-1 leave the server). If the
 *       service is down or slow the check fails open, so registration keeps working.
 * </ul>
 */
@Configuration
class PasswordSecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }

    @Bean
    CompromisedPasswordChecker compromisedPasswordChecker() {
        // no RestClient.Builder bean exists (spring-boot-starter-restclient is not a dependency), so start from a plain
        // one
        return compromisedPasswordChecker(RestClient.builder());
    }

    CompromisedPasswordChecker compromisedPasswordChecker(RestClient.Builder builder) {
        // the Add-Padding header makes every response look alike, so its size does not leak the hash prefix
        RestClient restClient = builder.baseUrl("https://api.pwnedpasswords.com/range/")
                .requestFactory(hibpRequestFactory())
                .defaultHeader("Add-Padding", "true")
                .requestInterceptor(PasswordSecurityConfig::dropPaddingEntries)
                .build();

        HaveIBeenPwnedRestApiPasswordChecker checker = new HaveIBeenPwnedRestApiPasswordChecker();
        checker.setRestClient(restClient);
        return checker;
    }

    // a slow HIBP must not hold the registration: 2 s to connect and 3 s to read, then the check fails open
    static JdkClientHttpRequestFactory hibpRequestFactory() {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        return requestFactory;
    }

    // padding entries have a count of 0 and are not real leaks, but the Spring checker would still match them
    private static ClientHttpResponse dropPaddingEntries(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        if (!response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        String filtered = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.endsWith(":0"))
                .collect(Collectors.joining("\n"));
        byte[] bytes = filtered.getBytes(StandardCharsets.UTF_8);
        return new ClientHttpResponse() {
            @Override
            public HttpStatusCode getStatusCode() throws IOException {
                return response.getStatusCode();
            }

            @Override
            public String getStatusText() throws IOException {
                return response.getStatusText();
            }

            @Override
            public HttpHeaders getHeaders() {
                return response.getHeaders();
            }

            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public void close() {
                response.close();
            }
        };
    }
}
