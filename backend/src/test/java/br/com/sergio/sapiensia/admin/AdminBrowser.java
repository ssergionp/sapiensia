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

    /** Puts a cookie in the jar by hand: replays a session cookie that the server has since ended. */
    void setCookie(String name, String value) {
        HttpCookie cookie = new HttpCookie(name, value);
        cookie.setPath("/");
        // version 0 (Netscape style): version 1 would send a "$Version" attribute the server parses as a cookie
        cookie.setVersion(0);
        jar.getCookieStore().add(uri("/"), cookie);
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

    /** A normal POST that also carries the given headers: what a client forging them would send. */
    HttpResponse<String> postWithHeaders(String path, String json, java.util.Map<String, String> headers)
            throws Exception {
        ensureCsrfCookie();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(builder::header);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A POST that keeps the cookies but sends no X-XSRF-TOKEN header: what a cross-site form would send. */
    HttpResponse<String> postWithoutCsrf(String path, String json) throws Exception {
        ensureCsrfCookie();
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> put(String path, String json) throws Exception {
        ensureCsrfCookie();
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"))
                        .PUT(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> patch(String path, String json) throws Exception {
        ensureCsrfCookie();
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"))
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A PUT that keeps the cookies but sends no X-XSRF-TOKEN header. */
    HttpResponse<String> putWithoutCsrf(String path, String json) throws Exception {
        ensureCsrfCookie();
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A POST with another Content-Type: what a client sending text/plain would do. */
    HttpResponse<String> postWithContentType(String path, String body, String contentType) throws Exception {
        ensureCsrfCookie();
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", contentType)
                        .header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A POST of a multipart body that sends the given CSRF token in the header, whatever the cookie holds. */
    HttpResponse<String> postMultipartWithToken(String path, UploadBodies.Upload upload, String token)
            throws Exception {
        ensureCsrfCookie();
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", upload.contentType())
                        .header("X-XSRF-TOKEN", token)
                        .POST(upload.publisher())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A POST of a multipart body (see {@link UploadBodies}), with or without the CSRF header. */
    HttpResponse<String> postMultipart(String path, UploadBodies.Upload upload, boolean withCsrf) throws Exception {
        ensureCsrfCookie();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", upload.contentType())
                .POST(upload.publisher());
        if (withCsrf) {
            builder.header("X-XSRF-TOKEN", cookie("XSRF-TOKEN"));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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

    /** A DELETE that keeps the cookies but sends no X-XSRF-TOKEN header. */
    HttpResponse<String> deleteWithoutCsrf(String path) throws Exception {
        ensureCsrfCookie();
        return client.send(HttpRequest.newBuilder(uri(path)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
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
