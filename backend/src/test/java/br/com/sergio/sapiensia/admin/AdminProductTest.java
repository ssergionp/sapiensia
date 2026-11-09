package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.json;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.validBody;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Create, list and view Products in the panel, against the real server and database. */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-product", "app.login-limit.ip-max-attempts=1000"})
class AdminProductTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SecretCipher cipher;

    @Autowired
    AdminMfaTestSupport.MutableClock clock;

    private AdminBrowser admin;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from products");
        AdminProductFixtures.Admin account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        admin = new AdminBrowser(port);
        assertThat(admin.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from products", Integer.class);
    }

    private void assertBadRequestWithoutInternals(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .doesNotContain("java.")
                .doesNotContain("org.")
                .doesNotContain("Exception")
                .doesNotContain("SQL")
                .doesNotContain("at br.com");
    }

    @Test
    @DisplayName("FR-016: cria em DRAFT com 201, Location e o Produto no corpo")
    void shouldCreateADraftProduct() throws Exception {
        HttpResponse<String> response = admin.post(PRODUCTS, validBody());

        assertThat(response.statusCode()).isEqualTo(201);
        String id = JsonPath.read(response.body(), "$.id");
        assertThat(response.headers().firstValue("Location")).contains(PRODUCTS + "/" + id);
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("DRAFT");
        assertThat((Integer) JsonPath.read(response.body(), "$.version")).isZero();
        assertThat((String) JsonPath.read(response.body(), "$.title")).isEqualTo("Guia de Pix");
        assertThat((String) JsonPath.read(response.body(), "$.category")).isEqualTo("FINANCE");
        assertThat((String) JsonPath.read(response.body(), "$.createdAt")).isNotBlank();
        assertThat(count()).isEqualTo(1);
        HttpResponse<String> read = admin.get(PRODUCTS + "/" + id);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).isEqualTo(response.body());
    }

    @Test
    @DisplayName("FR-018: o preço em centavos volta exato; 49.9 e 3000000000 dão 400 sem detalhe interno")
    void shouldKeepThePriceExactInCents() throws Exception {
        HttpResponse<String> response = admin.post(PRODUCTS, json("T", "D", "LAW", 999_999));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((Integer) JsonPath.read(response.body(), "$.priceCents")).isEqualTo(999_999);
        int before = count();

        assertBadRequestWithoutInternals(admin.post(PRODUCTS, json("T", "D", "LAW", "49.9")));
        assertBadRequestWithoutInternals(admin.post(PRODUCTS, json("T", "D", "LAW", "3000000000")));
        assertBadRequestWithoutInternals(admin.post(PRODUCTS, json("T", "D", "LAW", "\"49,90\"")));

        assertThat(count()).isEqualTo(before);
    }

    @Test
    @DisplayName("SEC-012: id, status e version mandados no POST são ignorados")
    void shouldIgnoreExtraFieldsOnCreate() throws Exception {
        UUID forged = UUID.randomUUID();
        String body = validBody()
                .replaceFirst(
                        "\\{",
                        "{\"id\":\"" + forged + "\",\"status\":\"PUBLISHED\","
                                + "\"version\":7,\"createdAt\":\"2000-01-01T00:00:00Z\",");

        HttpResponse<String> response = admin.post(PRODUCTS, body);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(response.body(), "$.id")).isNotEqualTo(forged.toString());
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("DRAFT");
        assertThat((Integer) JsonPath.read(response.body(), "$.version")).isZero();
        assertThat((String) JsonPath.read(response.body(), "$.createdAt")).doesNotStartWith("2000");
    }

    @Test
    @DisplayName("SEC-013: cada campo inválido devolve o seu código de erro")
    void shouldAnswerTheErrorCodeOfEachField() throws Exception {
        record Case(String body, String field, String code) {}
        List<Case> cases = List.of(
                new Case(json("", "D", "LAW", 500), "title", "TITLE_REQUIRED"),
                new Case(json("a".repeat(121), "D", "LAW", 500), "title", "TITLE_TOO_LONG"),
                new Case(json("a\u0001b", "D", "LAW", 500), "title", "TITLE_INVALID"),
                new Case(json("T", "  ", "LAW", 500), "description", "DESCRIPTION_REQUIRED"),
                new Case(json("T", "a".repeat(5001), "LAW", 500), "description", "DESCRIPTION_TOO_LONG"),
                new Case(json("T", "a\u001bb", "LAW", 500), "description", "DESCRIPTION_INVALID"),
                new Case(json("T", "D", null, 500), "category", "CATEGORY_REQUIRED"),
                new Case(json("T", "D", "LAW", null), "priceCents", "PRICE_REQUIRED"),
                new Case(json("T", "D", "LAW", 499), "priceCents", "PRICE_TOO_LOW"),
                new Case(json("T", "D", "LAW", -1), "priceCents", "PRICE_TOO_LOW"),
                new Case(json("T", "D", "LAW", 1_000_000), "priceCents", "PRICE_TOO_HIGH"));

        for (Case c : cases) {
            HttpResponse<String> response = admin.post(PRODUCTS, c.body());
            assertThat(response.statusCode()).as(c.code()).isEqualTo(400);
            assertThat((String) JsonPath.read(response.body(), "$.errors." + c.field()))
                    .as(c.code())
                    .isEqualTo(c.code());
        }
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("SEC-013: NUL e surrogate solto dão 400, nunca 500, e nada é gravado")
    void shouldAnswer400ForNulAndLoneSurrogate() throws Exception {
        for (String text : new String[] {"a\u0000b", "a\ud800b", "a\udc00"}) {
            assertBadRequestWithoutInternals(admin.post(PRODUCTS, json(text, "D", "LAW", 500)));
            assertBadRequestWithoutInternals(admin.post(PRODUCTS, json("T", text, "LAW", 500)));
        }
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("SEC-013: categoria desconhecida e corpo que não é JSON dão 400 genérico")
    void shouldAnswer400ForUnknownCategoryAndMalformedJson() throws Exception {
        assertBadRequestWithoutInternals(admin.post(PRODUCTS, json("T", "D", "COMICS", 500)));
        assertBadRequestWithoutInternals(admin.post(PRODUCTS, "{not json"));
        assertBadRequestWithoutInternals(admin.post(PRODUCTS, ""));
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("SEC-013: o texto é normalizado em NFC e sem espaços nas pontas antes de gravar")
    void shouldStoreNormalizedText() throws Exception {
        HttpResponse<String> response = admin.post(PRODUCTS, json("  Café  ", "\n d \n", "OTHER", 500));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat((String) JsonPath.read(response.body(), "$.title")).isEqualTo("Café");
        assertThat((String) JsonPath.read(response.body(), "$.description")).isEqualTo("d");
    }

    @Test
    @DisplayName("SEC-020: Content-Type text/plain dá 415")
    void shouldRefuseTextPlain() throws Exception {
        HttpResponse<String> response = admin.postWithContentType(PRODUCTS, validBody(), "text/plain");

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("SEC-015: 20 por página, do mais novo ao mais velho; página além do fim e 2147483647 dão 200 vazio")
    void shouldListTwentyPerPageNewestFirst() throws Exception {
        for (int i = 0; i < 25; i++) {
            jdbc.update(
                    "insert into products (title, description, category, price_cents, created_at)"
                            + " values (?, 'd', 'LAW', 500, now() - make_interval(secs => ?))",
                    "P" + i,
                    (25 - i));
        }

        HttpResponse<String> first = admin.get(PRODUCTS);
        HttpResponse<String> second = admin.get(PRODUCTS + "?page=1");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(first.body(), "$.page")).isZero();
        assertThat((Integer) JsonPath.read(first.body(), "$.totalPages")).isEqualTo(2);
        List<String> firstTitles = JsonPath.read(first.body(), "$.items[*].title");
        List<String> secondTitles = JsonPath.read(second.body(), "$.items[*].title");
        assertThat(firstTitles).hasSize(20).startsWith("P24", "P23").endsWith("P5");
        assertThat(secondTitles).containsExactly("P4", "P3", "P2", "P1", "P0");
        assertThat((Integer) JsonPath.read(second.body(), "$.page")).isEqualTo(1);

        for (String page : new String[] {"2", "2147483647"}) {
            HttpResponse<String> beyond = admin.get(PRODUCTS + "?page=" + page);
            assertThat(beyond.statusCode()).as(page).isEqualTo(200);
            assertThat((List<?>) JsonPath.read(beyond.body(), "$.items"))
                    .as(page)
                    .isEmpty();
        }
        // the size is not a parameter: the answer is the same with a forged one
        List<String> forged = JsonPath.read(admin.get(PRODUCTS + "?size=1000").body(), "$.items[*].title");
        assertThat(forged).hasSize(20);
    }

    @Test
    @DisplayName("SEC-015: página negativa ou não numérica dá 400 sem detalhe interno")
    void shouldRefuseABadPage() throws Exception {
        for (String page : new String[] {"-1", "abc", "1.5", "2147483648"}) {
            assertBadRequestWithoutInternals(admin.get(PRODUCTS + "?page=" + page));
        }
    }

    @Test
    @DisplayName("FR-016: a lista vazia tem totalPages 0")
    void shouldListNothingWhenThereAreNoProducts() throws Exception {
        HttpResponse<String> response = admin.get(PRODUCTS);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.totalPages")).isZero();
        assertThat((List<?>) JsonPath.read(response.body(), "$.items")).isEmpty();
    }

    @Test
    @DisplayName("FR-016: ID inexistente dá 404 e ID malformado dá 400, sem detalhe interno")
    void shouldAnswer404ForUnknownIdAnd400ForMalformedId() throws Exception {
        HttpResponse<String> missing = admin.get(PRODUCTS + "/" + UUID.randomUUID());
        assertThat(missing.statusCode()).isEqualTo(404);

        for (String id : new String[] {"abc", "123", "00000000-0000-0000-0000-00000000000g"}) {
            assertBadRequestWithoutInternals(admin.get(PRODUCTS + "/" + id));
        }
    }

    @Test
    @DisplayName("SEC-018: sem o banco a criação e a lista dão 503 sem corpo")
    void shouldAnswer503WithoutBodyWhenTheDatabaseFails() throws Exception {
        jdbc.execute("alter table products rename to products_off");
        try {
            HttpResponse<String> create = admin.post(PRODUCTS, validBody());
            HttpResponse<String> list = admin.get(PRODUCTS);

            assertThat(create.statusCode()).isEqualTo(503);
            assertThat(create.body()).isEmpty();
            assertThat(list.statusCode()).isEqualTo(503);
            assertThat(list.body()).isEmpty();
        } finally {
            jdbc.execute("alter table products_off rename to products");
        }
    }
}
