package br.com.sergio.sapiensia.email;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Local development and CI: writes each e-mail as a text file instead of sending it. */
@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "file")
class FileEmailSender implements EmailSender {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

    private final Path outboxDir;

    // bound as a String on purpose: Spring's own String-to-Path conversion treats "../x" as a resource path ("/../x")
    @Autowired
    FileEmailSender(@Value("${app.email.outbox-dir}") String outboxDir) {
        this(Path.of(outboxDir).toAbsolutePath().normalize());
    }

    FileEmailSender(Path outboxDir) {
        this.outboxDir = outboxDir;
    }

    @Override
    public void send(EmailMessage message) {
        String safeTo = message.to().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9@.-]", "_");
        Path file = outboxDir.resolve(TIMESTAMP.format(LocalDateTime.now()) + "-" + safeTo + ".txt");
        String content = "To: " + message.to() + "\nSubject: " + message.subject() + "\n\n" + message.text();
        try {
            Files.createDirectories(outboxDir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // the cause is dropped on purpose: its message carries the file path, which contains the recipient
            throw new EmailDeliveryException(
                    "Could not write the e-mail to the outbox: " + e.getClass().getSimpleName());
        }
    }
}
