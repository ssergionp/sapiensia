package br.com.sergio.sapiensia.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts the sender the way the application does (a real SpringApplication, so Boot's conversion service is used),
 * because building it by hand hid a bug in how the outbox folder property was bound.
 */
class FileEmailSenderConfigurationTest {

    private static final EmailMessage MESSAGE = new EmailMessage("ana@example.com", "Assunto", "Texto");

    @Test
    void shouldResolveARelativeOutboxFolderAgainstTheWorkingDirectory() throws IOException {
        Path expected = Path.of("target/e2e-outbox-test").toAbsolutePath().normalize();
        deleteRecursively(expected);
        try {
            sendWithOutboxDir("target/e2e-outbox-test");

            assertThat(Files.list(expected).toList()).hasSize(1);
        } finally {
            deleteRecursively(expected);
        }
    }

    @Test
    void shouldResolveAParentRelativeOutboxFolderLikeTheDefault() throws IOException {
        Path expected = Path.of("../.tmp/outbox-config-test").toAbsolutePath().normalize();
        deleteRecursively(expected);
        try {
            sendWithOutboxDir("../.tmp/outbox-config-test");

            assertThat(Files.list(expected).toList()).hasSize(1);
        } finally {
            deleteRecursively(expected);
        }
    }

    @Test
    void shouldKeepAnAbsoluteOutboxFolder(@TempDir Path outbox) throws IOException {
        sendWithOutboxDir(outbox.toAbsolutePath().toString());

        assertThat(Files.list(outbox).toList()).hasSize(1);
    }

    private static void sendWithOutboxDir(String outboxDir) {
        // a servlet application, like the real one: there Spring resolves a String-to-Path value as a web resource
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                        FileEmailSender.class, TomcatServletWebServerAutoConfiguration.class)
                .web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--app.email.provider=file", "--app.email.outbox-dir=" + outboxDir)) {
            context.getBean(EmailSender.class).send(MESSAGE);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
