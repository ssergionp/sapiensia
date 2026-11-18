package br.com.sergio.sapiensia.admin;

import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PASSWORD;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.PRODUCTS;
import static br.com.sergio.sapiensia.admin.AdminProductFixtures.validUpdate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.SecretCipher;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
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

/** FR-011, FR-016, SEC-065: publishing and unpublishing a Product, against the real server and database. */
@Import({TestcontainersConfiguration.class, AdminMfaTestSupport.ClockConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.isolated-context=admin-product-publication", "app.login-limit.ip-max-attempts=1000"})
class AdminProductPublicationTest {

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
    private UUID adminId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from product_covers");
        jdbc.update("delete from product_files");
        jdbc.update("delete from products");
        AdminProductFixtures.Admin account = AdminProductFixtures.insertAdmin(jdbc, passwordEncoder, cipher);
        adminId = account.id();
        admin = new AdminBrowser(port);
        assertThat(admin.loginAdminFully(account.email(), PASSWORD, account.secret(), clock)
                        .statusCode())
                .isEqualTo(204);
    }

    private static String path(UUID id) {
        return PRODUCTS + "/" + id + "/publication";
    }

    private static String correlationId(HttpResponse<String> response) {
        return response.headers().firstValue("X-Correlation-Id").orElseThrow();
    }

    private List<Map<String, Object>> rowsOf(HttpResponse<String> response) {
        return jdbc.queryForList("select * from audit_events where correlation_id = ?::uuid", correlationId(response));
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from products where id = ?", String.class, id);
    }

    private Object firstPublishedAt(UUID id) {
        return jdbc.queryForObject("select first_published_at from products where id = ?", Object.class, id);
    }

    @Test
    @DisplayName("FR-011: publicar sem Arquivo do produto é recusado e nada muda")
    void shouldRefusePublishingWithoutAFile() throws Exception {
        UUID id = AdminProductFixtures.insertProduct(jdbc);
        AdminProductFixtures.addCover(jdbc, id);

        HttpResponse<String> response = admin.put(path(id), "");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat((String) JsonPath.read(response.body(), "$.errors.file")).isEqualTo("FILE_REQUIRED");
        assertThat(status(id)).isEqualTo("DRAFT");
        assertThat(firstPublishedAt(id)).isNull();
        List<Map<String, Object>> rows = rowsOf(response);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("action", "ADMIN_PRODUCT_PUBLISHED")
                .containsEntry("result", "FAILURE")
                .containsEntry("actor_id", adminId)
                .containsEntry("object_id", id);
    }

    @Test
    @DisplayName("FR-011: recusa publicar um Rascunho sem capa")
    void shouldRefusePublishingWithoutACover() throws Exception {
        UUID id = AdminProductFixtures.insertProductWithFile(jdbc);

        HttpResponse<String> response = admin.put(path(id), "");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat((String) JsonPath.read(response.body(), "$.errors.cover")).isEqualTo("COVER_REQUIRED");
        assertThatThrownBy(() -> JsonPath.read(response.body(), "$.errors.file"))
                .isInstanceOf(PathNotFoundException.class);
        assertThat(status(id)).isEqualTo("DRAFT");
        List<Map<String, Object>> rows = rowsOf(response);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("action", "ADMIN_PRODUCT_PUBLISHED")
                .containsEntry("result", "FAILURE")
                .containsEntry("object_id", id);
    }

    @Test
    @DisplayName("FR-011: sem arquivo e sem capa, a recusa traz os dois motivos")
    void shouldReportBothReasonsWithoutFileAndCover() throws Exception {
        UUID id = AdminProductFixtures.insertProduct(jdbc);

        HttpResponse<String> response = admin.put(path(id), "");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat((String) JsonPath.read(response.body(), "$.errors.file")).isEqualTo("FILE_REQUIRED");
        assertThat((String) JsonPath.read(response.body(), "$.errors.cover")).isEqualTo("COVER_REQUIRED");
        assertThat(status(id)).isEqualTo("DRAFT");
        List<Map<String, Object>> rows = rowsOf(response);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("result", "FAILURE");
    }

    @Test
    @DisplayName("FR-011: Produto despublicado sem capa não é publicado de novo")
    void shouldNotRepublishAnUnpublishedProductWithoutACover() throws Exception {
        UUID id = AdminProductFixtures.insertProductWithFile(jdbc);
        jdbc.update("update products set status = 'UNPUBLISHED', first_published_at = now() where id = ?", id);

        HttpResponse<String> response = admin.put(path(id), "");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat((String) JsonPath.read(response.body(), "$.errors.cover")).isEqualTo("COVER_REQUIRED");
        assertThat(status(id)).isEqualTo("UNPUBLISHED");
    }

    @Test
    @DisplayName("FR-011: Produto já publicado sem capa continua publicado, mas publicar de novo é recusado")
    void shouldKeepAPublishedProductWithoutCoverPublished() throws Exception {
        UUID id = AdminProductFixtures.insertProductWithFile(jdbc);
        AdminProductFixtures.publish(jdbc, id);

        HttpResponse<String> response = admin.put(path(id), "");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat((String) JsonPath.read(response.body(), "$.errors.cover")).isEqualTo("COVER_REQUIRED");
        assertThat(status(id)).isEqualTo("PUBLISHED");
        assertThat(new AdminBrowser(port).get("/api/products").body()).contains(id.toString());
    }

    @Test
    @DisplayName("FR-011 FR-016: publicar com arquivo deixa o Produto publicado")
    void shouldPublishAProductWithAFile() throws Exception {
        UUID id = AdminProductFixtures.insertProductWithFileAndCover(jdbc);

        HttpResponse<String> response = admin.put(path(id), "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("PUBLISHED");
        assertThat((String) JsonPath.read(response.body(), "$.firstPublishedAt"))
                .isNotNull();
        assertThat(status(id)).isEqualTo("PUBLISHED");
        List<Map<String, Object>> rows = rowsOf(response);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("action", "ADMIN_PRODUCT_PUBLISHED")
                .containsEntry("result", "SUCCESS")
                .containsEntry("actor_id", adminId)
                .containsEntry("object_type", "PRODUCT")
                .containsEntry("object_id", id);
        // only the standard columns: nothing about the file
        assertThat(rows.getFirst().keySet())
                .containsExactlyInAnyOrder(
                        "id",
                        "occurred_at",
                        "actor_id",
                        "action",
                        "object_type",
                        "object_id",
                        "result",
                        "correlation_id",
                        "origin");
    }

    @Test
    @DisplayName("FR-016: despublicar tira o Produto de publicado e mantém a data da primeira publicação")
    void shouldUnpublishAndKeepTheFirstPublicationDate() throws Exception {
        UUID id = AdminProductFixtures.insertProductWithFileAndCover(jdbc);
        String first = JsonPath.read(admin.put(path(id), "").body(), "$.firstPublishedAt");

        HttpResponse<String> response = admin.delete(path(id));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("UNPUBLISHED");
        assertThat((String) JsonPath.read(response.body(), "$.firstPublishedAt"))
                .isEqualTo(first);
        List<Map<String, Object>> rows = rowsOf(response);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("action", "ADMIN_PRODUCT_UNPUBLISHED")
                .containsEntry("result", "SUCCESS");
    }

    @Test
    @DisplayName("FR-016: publicar de novo mantém a data da primeira publicação")
    void shouldKeepTheFirstPublicationDateWhenPublishingAgain() throws Exception {
        UUID id = AdminProductFixtures.insertProductWithFileAndCover(jdbc);
        String first = JsonPath.read(admin.put(path(id), "").body(), "$.firstPublishedAt");
        assertThat(admin.delete(path(id)).statusCode()).isEqualTo(200);

        HttpResponse<String> response = admin.put(path(id), "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("PUBLISHED");
        assertThat((String) JsonPath.read(response.body(), "$.firstPublishedAt"))
                .isEqualTo(first);
    }

    @Test
    @DisplayName("SEC-065: publicar ou despublicar duas vezes responde 200 e audita uma vez")
    void shouldAnswer200AndAuditOnceWhenRepeated() throws Exception {
        UUID id = AdminProductFixtures.insertProductWithFileAndCover(jdbc);

        HttpResponse<String> firstPut = admin.put(path(id), "");
        HttpResponse<String> secondPut = admin.put(path(id), "");
        HttpResponse<String> firstDelete = admin.delete(path(id));
        HttpResponse<String> secondDelete = admin.delete(path(id));

        assertThat(List.of(
                        firstPut.statusCode(),
                        secondPut.statusCode(),
                        firstDelete.statusCode(),
                        secondDelete.statusCode()))
                .containsOnly(200);
        assertThat(rowsOf(firstPut)).hasSize(1);
        assertThat(rowsOf(secondPut)).isEmpty();
        assertThat(rowsOf(firstDelete)).hasSize(1);
        assertThat(rowsOf(secondDelete)).isEmpty();
        assertThat((String) JsonPath.read(secondPut.body(), "$.status")).isEqualTo("PUBLISHED");
        assertThat((String) JsonPath.read(secondDelete.body(), "$.status")).isEqualTo("UNPUBLISHED");
    }

    @Test
    @DisplayName("FR-016: despublicar um rascunho não muda nada nem audita")
    void shouldNotChangeADraftWhenUnpublishing() throws Exception {
        UUID id = AdminProductFixtures.insertProductWithFileAndCover(jdbc);

        HttpResponse<String> response = admin.delete(path(id));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("DRAFT");
        assertThat(rowsOf(response)).isEmpty();
    }

    @Test
    @DisplayName("FR-016: publicar não muda a versão do Produto")
    void shouldNotBumpTheVersion() throws Exception {
        UUID id = AdminProductFixtures.insertProductWithFileAndCover(jdbc);

        HttpResponse<String> response = admin.put(path(id), "");

        assertThat((Integer) JsonPath.read(response.body(), "$.version")).isZero();
        assertThat(admin.put(PRODUCTS + "/" + id, validUpdate(0)).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("FR-016 SEC-010: a published Product shows in the public catalog and detail; unpublished it leaves"
            + " both with 404; republished it comes back")
    void shouldFollowThePublicationInThePublicCatalog() throws Exception {
        UUID id = AdminProductFixtures.insertProductWithFileAndCover(jdbc);
        AdminBrowser visitor = new AdminBrowser(port);

        assertThat(admin.put(path(id), "").statusCode()).isEqualTo(200);
        assertThat(visitor.get("/api/products").body()).contains(id.toString());
        assertThat(visitor.get("/api/products/" + id).statusCode()).isEqualTo(200);

        assertThat(admin.delete(path(id)).statusCode()).isEqualTo(200);
        assertThat(visitor.get("/api/products").body()).doesNotContain(id.toString());
        HttpResponse<String> gone = visitor.get("/api/products/" + id);
        assertThat(gone.statusCode()).isEqualTo(404);
        assertThat(gone.body()).isEmpty();

        assertThat(admin.put(path(id), "").statusCode()).isEqualTo(200);
        assertThat(visitor.get("/api/products/" + id).statusCode()).isEqualTo(200);

        // the public GET with the full Administrator session: allowed, and the session is untouched
        assertThat(admin.get("/api/products").statusCode()).isEqualTo(200);
        assertThat(admin.get("/api/admin/sessions/current").statusCode()).isEqualTo(200);
    }
}
