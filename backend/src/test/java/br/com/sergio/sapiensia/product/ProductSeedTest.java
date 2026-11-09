package br.com.sergio.sapiensia.product;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** The local seed of Products (NFR-012): fictitious data, applied only where db/seed is a Flyway location. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProductSeedTest {

    private static final List<UUID> SEED_IDS = List.of(
            UUID.fromString("00000000-0000-4000-8000-000000000101"),
            UUID.fromString("00000000-0000-4000-8000-000000000102"),
            UUID.fromString("00000000-0000-4000-8000-000000000103"),
            UUID.fromString("00000000-0000-4000-8000-000000000104"),
            UUID.fromString("00000000-0000-4000-8000-000000000105"));

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("NFR-012: shouldNotCreateSeedProductsInTheDefaultTestContext")
    void shouldNotCreateSeedProductsInTheDefaultTestContext() {
        assertThat(seedRows(jdbc, "public")).isZero();
    }

    @Test
    @DisplayName("NFR-012: shouldApplySeedOnceInItsOwnSchema")
    void shouldApplySeedOnceInItsOwnSchema() {
        String schema = newSchema();

        migrate(schema);
        assertThat(jdbc.queryForObject("select count(*) from " + schema + ".products", Integer.class))
                .isEqualTo(5);
        assertThat(seedRows(jdbc, schema)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                        "select count(*) from " + schema + ".products where title like '[Fictício]%'"
                                + " and price_cents between 500 and 999999 and status = 'DRAFT'"
                                + " and length(trim(description)) > 0",
                        Integer.class))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(distinct category) from " + schema + ".products", Integer.class))
                .isGreaterThanOrEqualTo(4);

        forceSeedRerun(schema);
        migrate(schema);
        assertThat(jdbc.queryForObject("select count(*) from " + schema + ".products", Integer.class))
                .isEqualTo(5);
    }

    @Test
    @DisplayName("NFR-012: shouldKeepLocalEditsWhenTheSeedRunsAgain")
    void shouldKeepLocalEditsWhenTheSeedRunsAgain() {
        String schema = newSchema();
        migrate(schema);

        jdbc.update("update " + schema + ".products set title = 'Edited locally' where id = ?", SEED_IDS.get(0));
        forceSeedRerun(schema);
        migrate(schema);

        assertThat(jdbc.queryForObject(
                        "select title from " + schema + ".products where id = ?", String.class, SEED_IDS.get(0)))
                .isEqualTo("Edited locally");
    }

    @Test
    @DisplayName("NFR-012: shouldNotReferenceTheSeedLocationInDefaultAndProdConfiguration")
    void shouldNotReferenceTheSeedLocationInDefaultAndProdConfiguration() throws IOException {
        assertThat(resourceText("application.yml")).doesNotContain("db/seed");
        assertThat(resourceText("application-prod.yml")).doesNotContain("db/seed");
        assertThat(new ClassPathResource("db/seed/R__seed_products.sql").exists())
                .isTrue();
        assertThat(new ClassPathResource("db/migration/R__seed_products.sql").exists())
                .isFalse();
    }

    private String newSchema() {
        String schema = "seed_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create schema " + schema);
        return schema;
    }

    private void migrate(String schema) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration", "classpath:db/seed")
                .load()
                .migrate();
    }

    /** A repeatable migration only reruns when its checksum changes; this forces the rerun to prove idempotence. */
    private void forceSeedRerun(String schema) {
        jdbc.update("update " + schema + ".flyway_schema_history set checksum = 0 where script like 'R__%'");
    }

    private static int seedRows(JdbcTemplate jdbc, String schema) {
        return jdbc.queryForObject(
                "select count(*) from " + schema + ".products where id = any (?::uuid[])", Integer.class, (Object)
                        SEED_IDS.stream().map(UUID::toString).toArray(String[]::new));
    }

    private static String resourceText(String name) throws IOException {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** NFR-012 in the prod profile: the full prod context boots and the seed is absent. */
    @Nested
    @SpringBootTest(
            properties = {
                "app.email.resend.api-key=re_test_fictitious",
                "app.public-base-url=http://localhost:5173",
                "app.admin.email=admin@example.com"
            })
    @ActiveProfiles("prod")
    @Import(TestcontainersConfiguration.class)
    class ProdProfile {

        @Autowired
        private JdbcTemplate prodJdbc;

        @Autowired
        private Environment environment;

        @Test
        @DisplayName("NFR-012: shouldNotApplyTheSeedInTheProdProfile")
        void shouldNotApplyTheSeedInTheProdProfile() {
            assertThat(prodJdbc.queryForObject(
                            "select count(*) from flyway_schema_history where script like '%seed_products%'",
                            Integer.class))
                    .isZero();
            assertThat(seedRows(prodJdbc, "public")).isZero();
            assertThat(prodJdbc.queryForObject(
                            "select count(*) from flyway_schema_history where script like 'V13__%'", Integer.class))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("NFR-012: shouldNotListTheSeedLocationInProdFlywayLocations")
        void shouldNotListTheSeedLocationInProdFlywayLocations() {
            assertThat(environment.getProperty("spring.flyway.locations", "classpath:db/migration"))
                    .doesNotContain("db/seed");
        }
    }
}
