package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-016: V15 on a database that already holds Products from before publishing existed. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProductPublicationMigrationTest {

    private String schema;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    /** The shared database outlives this class: a schema left behind shows up in other tests' catalog queries. */
    @AfterEach
    void dropSchema() {
        if (schema != null) {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    @Test
    @DisplayName("FR-016: a V15 deixa os Produtos existentes em rascunho e nunca publicados")
    void shouldKeepExistingProductsAsNeverPublishedDrafts() {
        schema = "pub_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create schema " + schema);
        flyway("14").migrate();
        UUID withoutFile = insertProduct();
        UUID withFile = insertProduct();
        jdbc.update(
                "insert into " + schema + ".product_files (product_id, object_key, size_bytes, page_count)"
                        + " values (?, ?, 100, 1)",
                withFile,
                "products/" + withFile + "/" + UUID.randomUUID() + ".pdf");

        flyway("latest").migrate();

        for (UUID id : new UUID[] {withoutFile, withFile}) {
            assertThat(jdbc.queryForObject("select status from " + schema + ".products where id = ?", String.class, id))
                    .isEqualTo("DRAFT");
            assertThat(jdbc.queryForObject(
                            "select first_published_at is null from " + schema + ".products where id = ?",
                            Boolean.class,
                            id))
                    .isTrue();
        }
        assertThatThrownBy(() ->
                        jdbc.update("update " + schema + ".products set status = 'PUBLISHED' where id = ?", withFile))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(
                "update " + schema + ".products set status = 'PUBLISHED', first_published_at = now() where id = ?",
                withFile);
        assertThatThrownBy(
                        () -> jdbc.update("update " + schema + ".products set status = 'DRAFT' where id = ?", withFile))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update " + schema + ".products set status = 'UNPUBLISHED' where id = ?", withFile);
    }

    private UUID insertProduct() {
        return jdbc.queryForObject(
                "insert into " + schema + ".products (title, description, category, price_cents)"
                        + " values ('Original', 'Descricao', 'FINANCE', 4990) returning id",
                UUID.class);
    }
}
