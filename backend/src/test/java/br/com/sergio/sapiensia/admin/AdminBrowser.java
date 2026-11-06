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

    /** The second step of the panel login: the 6-digit code of the authenticator app. */
    HttpResponse<String> verifyMfa(String code) throws Exception {
        return post("/api/admin/sessions/mfa", "{\"code\":\"" + code + "\"}");
    }

    /**
     * Password, then the code of a fresh 30 s step (the clock moves one step first, since a step is accepted only
     * once): ends with a full Administrator session. Returns the answer to the code.
     */
    HttpResponse<String> loginAdminFully(
            String email, String password, byte[] secret, AdminMfaTestSupport.MutableClock clock) throws Exception {
        clock.advance(java.time.Duration.ofSeconds(30));
        HttpResponse<String> login = loginAdmin(email, password);
        if (login.statusCode() != 204) {
            throw new AssertionError("password step answered " + login.statusCode());
        }
        return verifyMfa(AdminMfaTestSupport.codeAt(secret, clock.instant()));
    }

    HttpResponse<String> loginCustomer(String email, String password) throws Exception {
        return post("/api/sessions", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }
}
