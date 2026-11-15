package br.com.sergio.sapiensia;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.authentication.password.CompromisedPasswordDecision;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container for the whole test JVM, with a fresh database per Spring context. The container is never
 * stopped by hand and never reused across JVMs: Ryuk removes it when the test JVM exits.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();

    private static PostgreSQLContainer container;

    /** A database on the shared container; {@link #drop()} removes it, even with open connections. */
    public record TestDatabase(String jdbcUrl, String username, String password, String name) {

        public void drop() {
            try (Connection connection = adminConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("drop database " + name + " with (force)");
            } catch (SQLException e) {
                throw new IllegalStateException("Could not drop test database " + name, e);
            }
        }
    }

    private static synchronized PostgreSQLContainer sharedContainer() {
        if (container == null) {
            PostgreSQLContainer started = new PostgreSQLContainer(DockerImageName.parse("postgres:18"))
                    .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=200");
            started.start();
            container = started;
        }
        return container;
    }

    private static Connection adminConnection() throws SQLException {
        PostgreSQLContainer postgres = sharedContainer();
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    public static TestDatabase newDatabase() {
        PostgreSQLContainer postgres = sharedContainer();
        String name = "test_" + DATABASE_COUNTER.incrementAndGet();
        try (Connection connection = adminConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create database " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create test database " + name, e);
        }
        // getJdbcUrl() ends in /<databaseName>, optionally followed by ?params.
        String baseUrl = postgres.getJdbcUrl();
        int nameStart = baseUrl.lastIndexOf('/', baseUrl.contains("?") ? baseUrl.indexOf('?') : baseUrl.length()) + 1;
        int nameEnd = baseUrl.contains("?") ? baseUrl.indexOf('?') : baseUrl.length();
        String url = baseUrl.substring(0, nameStart) + name + baseUrl.substring(nameEnd);
        return new TestDatabase(url, postgres.getUsername(), postgres.getPassword(), name);
    }

    @Bean(destroyMethod = "drop")
    TestDatabase testDatabase() {
        return newDatabase();
    }

    /**
     * Takes the database as a parameter so Spring records DataSource -> JdbcConnectionDetails -> TestDatabase: the pool
     * closes before the database is dropped.
     */
    @Bean
    JdbcConnectionDetails jdbcConnectionDetails(TestDatabase db) {
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return db.jdbcUrl();
            }

            @Override
            public String getUsername() {
                return db.username();
            }

            @Override
            public String getPassword() {
                return db.password();
            }
        };
    }

    /** Replaces the Have I Been Pwned checker so tests never call the internet; flags only "Password123456". */
    @Bean
    @Primary
    CompromisedPasswordChecker fakeCompromisedPasswordChecker() {
        return password -> new CompromisedPasswordDecision("Password123456".equals(password));
    }

    /** Keeps e-mails in memory; tests never send real e-mails nor write them to disk. */
    @Bean
    @Primary
    RecordingEmailSender recordingEmailSender() {
        return new RecordingEmailSender();
    }
}
