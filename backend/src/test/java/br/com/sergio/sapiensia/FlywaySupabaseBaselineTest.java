package br.com.sergio.sapiensia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Supabase leaves the function rls_auto_enable in the public schema. Flyway treats that schema as non-empty and, with
 * no history table, refuses to migrate unless the prod profile baselines at version 0. The Flyway settings used here
 * are read from application-prod.yml, so removing them from the profile makes these tests fail.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywaySupabaseBaselineTest {

    private final List<String> schemas = new ArrayList<>();
    private final List<String> roles = new ArrayList<>();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestcontainersConfiguration.TestDatabase postgres;

    @Test
    @DisplayName("shouldRefuseANonEmptySchemaWithoutTheBaselineSettings")
    void shouldRefuseANonEmptySchemaWithoutTheBaselineSettings() {
        String schema = schemaWithSupabaseFunction();

        assertThatThrownBy(() -> migrate(schema, false))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    @DisplayName("shouldApplyEveryMigrationOnASchemaThatAlreadyHasTheSupabaseFunction")
    void shouldApplyEveryMigrationOnASchemaThatAlreadyHasTheSupabaseFunction() throws IOException {
        String schema = schemaWithSupabaseFunction();

        migrate(schema, true);

        assertThat(jdbc.queryForObject(
                        "select count(*) from information_schema.tables where table_schema = ?"
                                + " and table_name = 'flyway_schema_history'",
                        Integer.class,
                        schema))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from " + schema + ".flyway_schema_history"
                                + " where type = 'SQL' and success and version <> '0'",
                        Integer.class))
                .isEqualTo(versionedMigrationCount());
        assertThat(jdbc.queryForObject(
                        "select count(*) from " + schema + ".flyway_schema_history"
                                + " where type = 'BASELINE' and version = '0'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from information_schema.tables where table_schema = ?"
                                + " and table_name in ('customers', 'administrators', 'products', 'product_files')",
                        Integer.class,
                        schema))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject(
                        "select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace"
                                + " where n.nspname = ? and p.proname = 'rls_auto_enable'",
                        Integer.class,
                        schema))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("shouldLetTheTableOwnerReadAndWriteATableWithRowLevelSecurityAndNoPolicy")
    void shouldLetTheTableOwnerReadAndWriteATableWithRowLevelSecurityAndNoPolicy() throws SQLException {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String role = "owner_" + suffix;
        String schema = "rls_" + suffix;
        String password = UUID.randomUUID().toString();
        roles.add(role);
        schemas.add(schema);
        jdbc.execute("create role " + role + " login password '" + password + "'");
        jdbc.execute("create schema " + schema + " authorization " + role);

        try (Connection connection = DriverManager.getConnection(postgres.jdbcUrl(), role, password);
                Statement statement = connection.createStatement()) {
            statement.execute("create table " + schema + ".notes (id int primary key, body text)");
            statement.execute("alter table " + schema + ".notes enable row level security");
            statement.execute("insert into " + schema + ".notes values (1, 'visible to the owner')");
            try (var rows = statement.executeQuery("select count(*) from " + schema + ".notes")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }

    @AfterEach
    void cleanUp() {
        schemas.forEach(schema -> jdbc.execute("drop schema if exists " + schema + " cascade"));
        roles.forEach(role -> jdbc.execute("drop role if exists " + role));
        schemas.clear();
        roles.clear();
    }

    private String schemaWithSupabaseFunction() {
        String schema = "baseline_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create schema " + schema);
        jdbc.execute("create function " + schema + ".rls_auto_enable() returns void language sql as 'select 1'");
        schemas.add(schema);
        return schema;
    }

    private void migrate(String schema, boolean withProdSettings) throws IOException {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration");
        if (withProdSettings) {
            var prod = new YamlPropertySourceLoader()
                    .load("prod", new ClassPathResource("application-prod.yml"))
                    .get(0);
            configuration
                    .baselineOnMigrate(
                            Boolean.parseBoolean(String.valueOf(prod.getProperty("spring.flyway.baseline-on-migrate"))))
                    .baselineVersion(String.valueOf(prod.getProperty("spring.flyway.baseline-version")));
        }
        configuration.load().migrate();
    }

    private int versionedMigrationCount() throws IOException {
        return new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql").length;
    }
}
