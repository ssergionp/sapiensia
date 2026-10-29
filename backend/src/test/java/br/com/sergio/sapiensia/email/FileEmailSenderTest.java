package br.com.sergio.sapiensia.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileEmailSenderTest {

    @TempDir
    Path outbox;

    @Test
    void shouldWriteTheMessageToAFileInTheOutbox() throws IOException {
        new FileEmailSender(outbox).send(new EmailMessage("ana@example.com", "Confirme seu e-mail", "Olá, Ana"));

        List<Path> files;
        try (var stream = Files.list(outbox)) {
            files = stream.toList();
        }
        assertThat(files).hasSize(1);
        assertThat(Files.readString(files.get(0), StandardCharsets.UTF_8))
                .startsWith("To: ana@example.com\nSubject: Confirme seu e-mail\n\n")
                .endsWith("Olá, Ana");
        assertThat(files.get(0).getFileName().toString())
                .matches("[A-Za-z0-9@._-]+")
                .endsWith("-ana@example.com.txt");
    }

    @Test
    void shouldReplaceUnsafeCharactersInTheFileName() throws IOException {
        new FileEmailSender(outbox).send(new EmailMessage("A/b c\\d:e@example.com", "s", "t"));

        try (var stream = Files.list(outbox)) {
            assertThat(stream.map(p -> p.getFileName().toString()).toList())
                    .hasSize(1)
                    .allMatch(name -> name.matches("[A-Za-z0-9@._-]+"));
        }
    }
}
