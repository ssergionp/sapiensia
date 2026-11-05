package br.com.sergio.sapiensia.admin;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** A browser with its own cookie jar against a real server: one JSESSIONID, like a person at one computer. */
final class AdminBrowser {

    private final int port;
    private final CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(jar).build();

    AdminBrowser(int port) {
        this.port = port;
    }

    String cookie(String name) {
        return jar.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private void ensureCsrfCookie() throws Exception {
        if (cookie("XSRF-TOKEN") == null) {
            get("/entrar");
        }
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        ensureCsrfCookie();
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> delete(String path) throws Exception {
        ensureCsrfCookie();
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"))
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> loginAdmin(String email, String password) throws Exception {
        return post("/api/admin/sessions", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    HttpResponse<String> loginCustomer(String email, String password) throws Exception {
        return post("/api/sessions", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }
}
