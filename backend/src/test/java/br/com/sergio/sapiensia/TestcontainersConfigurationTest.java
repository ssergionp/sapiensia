package br.com.sergio.sapiensia;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.sergio.sapiensia.TestcontainersConfiguration.TestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.SpringProperties;

class TestcontainersConfigurationTest {

    @Test
    void shouldGiveEachCallItsOwnDatabaseOnTheSameServer() {
        TestDatabase first = TestcontainersConfiguration.newDatabase();
        TestDatabase second = TestcontainersConfiguration.newDatabase();
        try {
            assertThat(hostAndPort(first.jdbcUrl())).isEqualTo(hostAndPort(second.jdbcUrl()));
            assertThat(first.name()).isNotEqualTo(second.name());
        } finally {
            first.drop();
            second.drop();
        }
    }

    @Test
    void shouldIsolateTablesBetweenDatabases() throws SQLException {
        TestDatabase first = TestcontainersConfiguration.newDatabase();
        TestDatabase second = TestcontainersConfiguration.newDatabase();
        try {
            try (Connection connection = connect(first);
                    Statement statement = connection.createStatement()) {
                statement.execute("create table probe(id int)");
            }
            try (Connection connection = connect(second);
                    Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("select to_regclass('probe') is null")) {
                result.next();
                assertThat(result.getBoolean(1)).isTrue();
            }
        } finally {
            first.drop();
            second.drop();
        }
    }

    @Test
    void shouldDropTheDatabaseEvenWithOpenConnections() throws SQLException {
        TestDatabase doomed = TestcontainersConfiguration.newDatabase();
        TestDatabase other = TestcontainersConfiguration.newDatabase();
        try (Connection open = connect(doomed)) {
            doomed.drop();

            assertThat(databaseCount(other, doomed.name())).isZero();
        } finally {
            other.drop();
        }
    }

    @Test
    void shouldStartTheSharedServerWithRoomForTheCachedContexts() throws SQLException {
        TestDatabase db = TestcontainersConfiguration.newDatabase();
        try (Connection connection = connect(db);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("show max_connections")) {
            result.next();
            assertThat(result.getString(1)).isEqualTo("200");
        } finally {
            db.drop();
        }
    }

    @Test
    void shouldCloseThePoolAndDropTheDatabaseWhenTheContextCloses() throws SQLException {
        TestDatabase other = TestcontainersConfiguration.newDatabase();
        try {
            HikariDataSource pool;
            String databaseName;
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.register(TestcontainersConfiguration.class, PoolConfiguration.class);
                context.refresh();
                pool = context.getBean(HikariDataSource.class);
                databaseName = context.getBean(TestDatabase.class).name();
                try (Connection connection = pool.getConnection()) {
                    assertThat(connection.isValid(2)).isTrue();
                }
            }

            assertThat(pool.isClosed()).isTrue();
            assertThat(databaseCount(other, databaseName)).isZero();
        } finally {
            other.drop();
        }
    }

    @Test
    void shouldConfigureTheContextCacheSizeAsEight() {
        assertThat(SpringProperties.getProperty("spring.test.context.cache.maxSize"))
                .isEqualTo("8");
    }

    @Configuration(proxyBeanMethods = false)
    static class PoolConfiguration {

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource(JdbcConnectionDetails details) {
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl(details.getJdbcUrl());
            pool.setUsername(details.getUsername());
            pool.setPassword(details.getPassword());
            return pool;
        }
    }

    private static String hostAndPort(String jdbcUrl) {
        return jdbcUrl.substring(0, jdbcUrl.indexOf('/', "jdbc:postgresql://".length()));
    }

    private static Connection connect(TestDatabase db) throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }

    private static int databaseCount(TestDatabase through, String databaseName) throws SQLException {
        try (Connection connection = connect(through);
                var statement = connection.prepareStatement("select count(*) from pg_database where datname = ?")) {
            statement.setString(1, databaseName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
