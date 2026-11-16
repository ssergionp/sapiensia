package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-016, FR-018, SEC-010, SEC-015: the public Catalog against the real server and database, as an anonymous Visitor. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=catalog"})
class CatalogTest {

    private static final String ELLIPSIS = "…";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    // a new client per test: a Visitor with an empty cookie jar
    private final CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(jar).build();

    @BeforeEach
    void clean() {
        jdbc.update("delete from product_files");
        jdbc.update("delete from products");
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String xsrfToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString("{}"));
        if (xsrfToken != null) {
            builder.header("X-XSRF-TOKEN", xsrfToken);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Inserts a Product; {@code publishedAgo} is a Postgres interval such as "2 days", null for a DRAFT. */
    private UUID insert(String title, String status, String publishedAgo) {
        return insert(title, "Descricao de " + title, status, publishedAgo);
    }

    private UUID insert(String title, String description, String status, String publishedAgo) {
        UUID id = jdbc.queryForObject(
                "insert into products (title, description, category, price_cents, status, first_published_at)"
                        + " values (?, ?, 'TECHNOLOGY', 1990, ?, now() - ?::interval) returning id",
                UUID.class,
                title,
                description,
                status,
                publishedAgo);
        if (!"DRAFT".equals(status)) {
            jdbc.update(
                    "insert into product_files (product_id, object_key, size_bytes, page_count) values (?, ?, 100, 1)",
                    id,
                    "products/" + id + "/" + UUID.randomUUID() + ".pdf");
        }
        return id;
    }

    private List<String> titles(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.items[*].title");
    }

    private static Optional<String> header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name);
    }

    @Test
    @DisplayName("FR-016 SEC-010: the catalog lists only PUBLISHED Products, latest first publication first")
    void shouldListOnlyPublishedProductsLatestFirst() throws Exception {
        insert("D", "DRAFT", null);
        insert("A", "PUBLISHED", "2 days");
        insert("B", "PUBLISHED", "1 day");
        insert("U", "UNPUBLISHED", "3 days");

        HttpResponse<String> response = get("/api/products");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(titles(response)).containsExactly("B", "A");
        assertThat((Integer) JsonPath.read(response.body(), "$.page")).isZero();
        assertThat((Integer) JsonPath.read(response.body(), "$.totalPages")).isEqualTo(1);
    }

    @Test
    @DisplayName("SEC-010: a draft, an unpublished and a missing Product get the same 404 with an empty body")
    void shouldAnswerTheSame404ForADraftAnUnpublishedAndAMissingProduct() throws Exception {
        UUID draft = insert("D", "DRAFT", null);
        UUID unpublished = insert("U", "UNPUBLISHED", "3 days");

        HttpResponse<String> forDraft = get("/api/products/" + draft);
        HttpResponse<String> forUnpublished = get("/api/products/" + unpublished);
        HttpResponse<String> forMissing = get("/api/products/" + UUID.randomUUID());

        for (HttpResponse<String> response : List.of(forDraft, forUnpublished, forMissing)) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).isEmpty();
            assertThat(header(response, "Content-Type")).isEqualTo(header(forDraft, "Content-Type"));
            assertThat(header(response, "Content-Length")).isEqualTo(header(forDraft, "Content-Length"));
        }
    }

    @Test
    @DisplayName("SEC-010 FR-018: public answers carry only the public fields")
    void shouldCarryOnlyThePublicFields() throws Exception {
        UUID id = insert("A", "PUBLISHED", "1 day");

        HttpResponse<String> list = get("/api/products");
        HttpResponse<String> detail = get("/api/products/" + id);

        assertThat(detail.statusCode()).isEqualTo(200);
        Map<String, Object> page = JsonPath.read(list.body(), "$");
        Map<String, Object> item = JsonPath.read(list.body(), "$.items[0]");
        Map<String, Object> product = JsonPath.read(detail.body(), "$");
        assertThat(page.keySet()).containsExactlyInAnyOrder("items", "page", "totalPages");
        assertThat(item.keySet())
                .containsExactlyInAnyOrder("id", "title", "summary", "category", "priceCents", "coverVersion");
        assertThat(product.keySet())
                .containsExactlyInAnyOrder("id", "title", "description", "category", "priceCents", "coverVersion");
        for (String body : List.of(list.body(), detail.body())) {
            assertThat(body)
                    .doesNotContain("products/")
                    .doesNotContain("status")
                    .doesNotContain("version")
                    .doesNotContain("PUBLISHED");
        }
        assertThat((Integer) JsonPath.read(detail.body(), "$.priceCents")).isEqualTo(1990);
        assertThat((String) JsonPath.read(detail.body(), "$.category")).isEqualTo("TECHNOLOGY");
    }

    @Test
    @DisplayName("FR-016: the list carries the summary, the detail the whole description")
    void shouldCarryTheSummaryInTheListAndTheWholeDescriptionInTheDetail() throws Exception {
        String description = "linha um\n" + "palavra ".repeat(37).strip();
        assertThat(description.length()).isGreaterThan(290);
        UUID id = insert("A", description, "PUBLISHED", "1 day");

        HttpResponse<String> list = get("/api/products");
        HttpResponse<String> detail = get("/api/products/" + id);

        String summary = JsonPath.read(list.body(), "$.items[0].summary");
        assertThat(summary).endsWith(ELLIPSIS);
        assertThat(summary.length()).isLessThanOrEqualTo(201);
        assertThat((String) JsonPath.read(detail.body(), "$.description")).isEqualTo(description);
    }

    @Test
    @DisplayName("SEC-015: pages of 20, a page past the end is empty, an invalid page is 400")
    void shouldPageByTwentyAndRejectAnInvalidPage() throws Exception {
        for (int i = 0; i < 21; i++) {
            insert("P" + i, "PUBLISHED", (i + 1) + " minutes");
        }

        HttpResponse<String> first = get("/api/products?page=0");
        HttpResponse<String> second = get("/api/products?page=1");

        assertThat(titles(first)).hasSize(20);
        assertThat((Integer) JsonPath.read(first.body(), "$.totalPages")).isEqualTo(2);
        assertThat(titles(second)).hasSize(1).doesNotContainAnyElementsOf(titles(first));
        for (String page : List.of("5", "10000")) {
            HttpResponse<String> past = get("/api/products?page=" + page);
            assertThat(past.statusCode()).isEqualTo(200);
            assertThat(titles(past)).isEmpty();
        }
        for (String page : List.of("-1", "abc", "2147483648", "10001", "2147483647")) {
            HttpResponse<String> invalid = get("/api/products?page=" + page);
            assertThat(invalid.statusCode()).as("page=" + page).isEqualTo(400);
            assertThat(invalid.body())
                    .doesNotContain("Exception")
                    .doesNotContain("java.")
                    .doesNotContain("at br.")
                    .doesNotContain("trace");
        }
    }

    @Test
    @DisplayName("SEC-010: a malformed id is 400")
    void shouldAnswer400ForAMalformedId() throws Exception {
        HttpResponse<String> response = get("/api/products/xyz");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("Exception").doesNotContain("java.");
    }

    @Test
    @DisplayName("SEC-010: an anonymous visitor reads without CSRF, without a session and without any cookie")
    void shouldReadWithoutAnyCookie() throws Exception {
        UUID id = insert("A", "PUBLISHED", "1 day");

        for (String path : List.of("/api/products", "/api/products/" + id, "/api/products/" + UUID.randomUUID())) {
            HttpResponse<String> response = get(path);

            assertThat(response.headers().allValues("Set-Cookie")).as(path).isEmpty();
            assertThat(header(response, "Cache-Control").orElse("")).contains("no-store");
            assertThat(header(response, "Content-Security-Policy").orElse("")).contains("default-src 'self'");
            assertThat(header(response, "X-Frame-Options")).contains("DENY");
            assertThat(header(response, "X-Content-Type-Options")).contains("nosniff");
        }
        assertThat(jar.getCookieStore().getCookies()).isEmpty();
    }

    @Test
    @DisplayName("SEC-010: only GET is open")
    void shouldOpenOnlyGet() throws Exception {
        UUID id = insert("A", "PUBLISHED", "1 day");
        List<String[]> writes = List.of(
                new String[] {"POST", "/api/products"},
                new String[] {"PUT", "/api/products/" + id},
                new String[] {"DELETE", "/api/products/" + id});

        for (String[] write : writes) {
            assertThat(send(write[0], write[1], null).statusCode())
                    .as(write[0] + " without CSRF")
                    .isEqualTo(403);
        }
        get("/api/system/health");
        String token = jar.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN"))
                .findFirst()
                .orElseThrow()
                .getValue();
        for (String[] write : writes) {
            assertThat(send(write[0], write[1], token).statusCode())
                    .as(write[0] + " with CSRF")
                    .isEqualTo(401);
        }
        assertThat(get("/api/products/" + id + "/file").statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from products", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select title from products where id = ?", String.class, id))
                .isEqualTo("A");
    }
}
