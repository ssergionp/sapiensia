package br.com.sergio.sapiensia.payment;

import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A real HTTP client of a logged-in Customer (session cookie and CSRF token), for the RANDOM_PORT order tests. */
final class OrderHttp {

    static final String PASSWORD = "senhalongaok"; // gitleaks:allow

    private OrderHttp() {}

    /** One Customer with its own cookie jar. */
    static final class Customer {

        final UUID id;
        final String email;
        private final int port;
        private final CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http =
                HttpClient.newBuilder().cookieHandler(jar).build();

        private Customer(UUID id, String email, int port) {
            this.id = id;
            this.email = email;
            this.port = port;
        }

        private URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }

        private String xsrf() {
            return jar.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                    .map(HttpCookie::getValue)
                    .reduce((first, last) -> last)
                    .orElseThrow();
        }

        private void login() throws Exception {
            http.send(HttpRequest.newBuilder(uri("/entrar")).build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(uri("/api/sessions"))
                            .header("Content-Type", "application/json")
                            .header("X-XSRF-TOKEN", xsrf())
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 204) {
                throw new IllegalStateException("login failed: " + response.statusCode());
            }
        }

        HttpResponse<String> post(String path, String json) throws Exception {
            return http.send(
                    HttpRequest.newBuilder(uri(path))
                            .timeout(Duration.ofSeconds(60))
                            .header("Content-Type", "application/json")
                            .header("X-XSRF-TOKEN", xsrf())
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        /** Any verb with a valid CSRF token and a JSON body, for the routes that must not change anything. */
        HttpResponse<String> send(String method, String path, String json) throws Exception {
            return http.send(
                    HttpRequest.newBuilder(uri(path))
                            .timeout(Duration.ofSeconds(60))
                            .header("Content-Type", "application/json")
                            .header("X-XSRF-TOKEN", xsrf())
                            .method(method, HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(
                    HttpRequest.newBuilder(uri(path))
                            .timeout(Duration.ofSeconds(60))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> order(UUID productId, String cpf) throws Exception {
            return post("/api/orders", body(productId, cpf));
        }

        HttpResponse<String> order(UUID productId) throws Exception {
            return order(productId, null);
        }
    }

    static String body(UUID productId, String cpf) {
        return cpf == null
                ? "{\"productId\":\"" + productId + "\"}"
                : "{\"productId\":\"" + productId + "\",\"cpf\":\"" + cpf + "\"}";
    }

    /** Inserts a confirmed Customer and logs it in. */
    static Customer newCustomer(int port, JdbcTemplate jdbc, PasswordEncoder encoder) throws Exception {
        String email = "cliente-" + UUID.randomUUID() + "@example.com";
        UUID id = jdbc.queryForObject(
                "insert into customers (email, password_hash, email_confirmed_at) values (?, ?, now()) returning id",
                UUID.class,
                email,
                encoder.encode(PASSWORD));
        Customer customer = new Customer(id, email, port);
        customer.login();
        return customer;
    }

    static String json(HttpResponse<String> response, String path) {
        Object value = JsonPath.read(response.body(), path);
        return value == null ? null : value.toString();
    }
}
