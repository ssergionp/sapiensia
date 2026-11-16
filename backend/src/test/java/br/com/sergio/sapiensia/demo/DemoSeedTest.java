package br.com.sergio.sapiensia.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.sergio.sapiensia.RecordingEmailSender;
import br.com.sergio.sapiensia.TestcontainersConfiguration;
import br.com.sergio.sapiensia.security.Totp;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.bouncycastle.util.encoders.Base32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;

/** The local demo seeders: they create the demo data in the local profile and can never exist in prod. */
class DemoSeedTest {

    private static final List<String> DEMO_TABLE_COUNTS = List.of(
            "select count(*) from administrators",
            "select count(*) from customers",
            "select count(*) from products where status = 'PUBLISHED'",
            "select count(*) from product_files",
            "select count(*) from product_covers");

    /** A browser with its own cookie jar against the real server. */
    private static final class Client {
        private final int port;
        private final CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http =
                HttpClient.newBuilder().cookieHandler(jar).build();

        Client(int port) {
            this.port = port;
        }

        private URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, String json) throws Exception {
            String xsrf = cookie("XSRF-TOKEN");
            if (xsrf == null) {
                get("/entrar");
                xsrf = cookie("XSRF-TOKEN");
            }
            return http.send(
                    HttpRequest.newBuilder(uri(path))
                            .header("Content-Type", "application/json")
                            .header("X-XSRF-TOKEN", xsrf)
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private String cookie(String name) {
            return jar.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals(name))
                    .map(HttpCookie::getValue)
                    .findFirst()
                    .orElse(null);
        }

        HttpResponse<String> login(String path, String email, String password) throws Exception {
            return post(path, "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        }
    }

    @Test
    @DisplayName("NFR-012: demo data refuses the prod profile even together with local")
    void shouldRefuseTheProdProfileEvenTogetherWithLocal() {
        MockEnvironment both = new MockEnvironment();
        both.setActiveProfiles("local", "prod");
        MockEnvironment localOnly = new MockEnvironment();
        localOnly.setActiveProfiles("local");

        assertThatThrownBy(() -> DemoAccounts.refuseInProd(both))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("demo data is never allowed in the prod profile");
        assertThatCode(() -> DemoAccounts.refuseInProd(localOnly)).doesNotThrowAnyException();
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"app.demo.enabled=true", "app.storage.file.dir=target/test-storage-demo"})
    @ActiveProfiles("local")
    @Import(TestcontainersConfiguration.class)
    class LocalDemo {

        @LocalServerPort
        int port;

        @Autowired
        JdbcTemplate jdbc;

        @Autowired
        ApplicationContext context;

        @Autowired
        RecordingEmailSender emails;

        @Test
        @DisplayName("shouldLetTheDemoAdministratorLogInWithPasswordAndTotp")
        void shouldLetTheDemoAdministratorLogInWithPasswordAndTotp() throws Exception {
            Client client = new Client(port);

            assertThat(client.login("/api/admin/sessions", DemoAccounts.ADMIN_EMAIL, DemoAccounts.ADMIN_PASSWORD)
                            .statusCode())
                    .isEqualTo(204);
            String code = Totp.code(Base32.decode(DemoAccounts.ADMIN_TOTP_SECRET), Totp.stepAt(Instant.now()), 6);
            assertThat(client.post("/api/admin/sessions/mfa", "{\"code\":\"" + code + "\"}")
                            .statusCode())
                    .isEqualTo(204);
            assertThat(client.get("/api/admin/sessions/current").statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("shouldLetTheDemoCustomerLogIn")
        void shouldLetTheDemoCustomerLogIn() throws Exception {
            Client client = new Client(port);

            assertThat(client.login("/api/sessions", DemoAccounts.CUSTOMER_EMAIL, DemoAccounts.CUSTOMER_PASSWORD)
                            .statusCode())
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("shouldListThreePublishedDemoProductsWithCovers")
        void shouldListThreePublishedDemoProductsWithCovers() throws Exception {
            HttpResponse<String> response = new Client(port).get("/api/products");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains(
                            "Guia de alimentação saudável",
                            "Cartilha de orçamento doméstico",
                            "Tutorial de senhas seguras")
                    .doesNotContain("Checklist de contrato", "Guia de estudo para concursos")
                    .doesNotContain("\"coverVersion\":null");
        }

        @Test
        @DisplayName("shouldNotSendTheFirstAccessEmailToTheDemoAdministrator")
        void shouldNotSendTheFirstAccessEmailToTheDemoAdministrator() throws Exception {
            assertThat(emails.awaitMessageTo(DemoAccounts.ADMIN_EMAIL, Duration.ofSeconds(3)))
                    .isEmpty();
        }

        @Test
        @DisplayName("shouldKeepTheRowCountsWhenTheSeedersRunAgain")
        void shouldKeepTheRowCountsWhenTheSeedersRunAgain() throws Exception {
            List<Long> before = counts();
            ApplicationArguments args = new DefaultApplicationArguments();

            Map<String, ApplicationRunner> runners = context.getBeansOfType(ApplicationRunner.class);
            List<ApplicationRunner> seeders = runners.values().stream()
                    .filter(r -> r.getClass().getSimpleName().startsWith("Demo"))
                    .toList();
            for (ApplicationRunner seeder : seeders) {
                seeder.run(args);
            }

            assertThat(seeders).hasSize(3);
            assertThat(counts()).isEqualTo(before);
            assertThat(before).containsExactly(1L, 1L, 3L, 3L, 3L);
        }

        @Test
        @DisplayName("shouldRepublishTheDemoFilesWhenTheStorageLostThem")
        void shouldRepublishTheDemoFilesWhenTheStorageLostThem() throws Exception {
            Path storageDir = Path.of("target/test-storage-demo").toAbsolutePath();
            try (Stream<Path> tree = Files.walk(storageDir)) {
                for (Path file : tree.filter(Files::isRegularFile).toList()) {
                    Files.delete(file);
                }
            }
            List<Long> before = counts();
            assertThat(currentObjectKeys()).hasSize(6).noneMatch(key -> Files.isRegularFile(storageDir.resolve(key)));

            ApplicationArguments args = new DefaultApplicationArguments();
            for (ApplicationRunner seeder :
                    context.getBeansOfType(ApplicationRunner.class).values()) {
                if (seeder.getClass().getSimpleName().startsWith("Demo")) {
                    seeder.run(args);
                }
            }

            assertThat(jdbc.queryForObject("select count(*) from products where status = 'PUBLISHED'", Long.class))
                    .isEqualTo(3L);
            assertThat(currentObjectKeys()).hasSize(6).allMatch(key -> Files.isRegularFile(storageDir.resolve(key)));
            assertThat(counts()).isEqualTo(before);
        }

        private List<String> currentObjectKeys() {
            return jdbc.queryForList(
                    "select object_key from product_files union all select object_key from product_covers",
                    String.class);
        }

        private List<Long> counts() {
            return DEMO_TABLE_COUNTS.stream()
                    .map(sql -> jdbc.queryForObject(sql, Long.class))
                    .toList();
        }
    }

    @Nested
    @SpringBootTest(properties = {"app.storage.file.dir=target/test-storage-nodemo"})
    @ActiveProfiles("local")
    @Import(TestcontainersConfiguration.class)
    class LocalWithoutDemoFlag {

        @Autowired
        JdbcTemplate jdbc;

        @Autowired
        ApplicationContext context;

        @Test
        @DisplayName("shouldCreateNoDemoSeederInLocalWithoutTheDemoFlag")
        void shouldCreateNoDemoSeederInLocalWithoutTheDemoFlag() {
            assertThat(context.getBeansOfType(ApplicationRunner.class).values())
                    .noneMatch(r -> r.getClass().getSimpleName().startsWith("Demo")
                            && r.getClass().getSimpleName().endsWith("Seeder"));
            assertThat(jdbc.queryForObject(
                            "select count(*) from customers where email = ?", Long.class, DemoAccounts.CUSTOMER_EMAIL))
                    .isZero();
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                "app.demo.enabled=true",
                "app.email.resend.api-key=re_test_fictitious",
                "app.storage.supabase.url=http://localhost:1",
                "app.storage.supabase.secret-key=test-secret-not-real",
                "app.asaas.api.base-url=http://localhost:1",
                "app.asaas.api.key=test-asaas-key-not-real",
                "app.public-base-url=http://localhost:5173",
                "app.admin.email=admin@example.com"
            })
    @ActiveProfiles("prod")
    @Import(TestcontainersConfiguration.class)
    class ProdProfile {

        @LocalServerPort
        int port;

        @Autowired
        JdbcTemplate jdbc;

        @Autowired
        ApplicationContext context;

        @Test
        @DisplayName("NFR-012: no demo seeder exists in the prod profile")
        void shouldHaveNoDemoSeederBeanInProd() {
            assertThat(context.getBeansOfType(ApplicationRunner.class).values())
                    .noneMatch(r -> r.getClass().getSimpleName().startsWith("Demo"));
        }

        @Test
        @DisplayName("NFR-012: no demo account rows exist in the prod profile")
        void shouldHaveNoDemoAccountRowsInProd() {
            assertThat(jdbc.queryForObject(
                            "select count(*) from customers where email = ?", Long.class, DemoAccounts.CUSTOMER_EMAIL))
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "select count(*) from administrators where password_hash is not null", Long.class))
                    .isZero();
        }

        @Test
        @DisplayName("NFR-012: the prod profile refuses the demo passwords")
        void shouldRefuseTheDemoPasswordsInProd() throws Exception {
            assertThat(new Client(port)
                            .login("/api/admin/sessions", DemoAccounts.ADMIN_EMAIL, DemoAccounts.ADMIN_PASSWORD)
                            .statusCode())
                    .isEqualTo(401);
            assertThat(new Client(port)
                            .login("/api/sessions", DemoAccounts.CUSTOMER_EMAIL, DemoAccounts.CUSTOMER_PASSWORD)
                            .statusCode())
                    .isEqualTo(401);
        }
    }
}
